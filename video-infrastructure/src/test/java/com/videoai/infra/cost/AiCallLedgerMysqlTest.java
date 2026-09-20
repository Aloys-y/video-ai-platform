package com.videoai.infra.cost;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoai.common.analysis.*;
import com.videoai.infra.mysql.mapper.AiCallLogMapper;
import org.apache.ibatis.session.Configuration;
import org.mybatis.spring.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 仅读取dev连接配置，创建本次随机库；不启动Worker/Redis/模型客户端。 */
@EnabledIfSystemProperty(named="cost.mysql.acceptance",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(90)
class AiCallLedgerMysqlTest {
    String database;JdbcTemplate admin,jdbc;AiCallLogMapper mapper;AiCallRecorder recorder;
    @BeforeAll void setup() throws Exception {
        var root=Path.of("..").toAbsolutePath().normalize();
        var env=new StandardEnvironment();
        for(var p:new YamlPropertySourceLoader().load("dev",new FileSystemResource(root.resolve("video-worker/src/main/resources/application-dev.yml"))))env.getPropertySources().addLast(p);
        String original=env.getRequiredProperty("spring.datasource.url");
        String base=original.substring(0,original.indexOf('/',"jdbc:mysql://".length())+1);
        String options="?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&connectTimeout=5000&socketTimeout=10000";
        String user=env.getRequiredProperty("spring.datasource.username"),pass=env.getRequiredProperty("spring.datasource.password");
        admin=new JdbcTemplate(new DriverManagerDataSource(base+options,user,pass));
        database="videoai_cost_it_"+UUID.randomUUID().toString().replace("-","");
        admin.execute("CREATE DATABASE `"+database+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        var ds=new DriverManagerDataSource(base+database+options,user,pass);jdbc=new JdbcTemplate(ds);
        String schema=Files.readString(root.resolve("sql/schema.sql")).replaceAll("(?im)^CREATE DATABASE[^;]*;","").replaceAll("(?im)^USE [^;]*;","");
        new ResourceDatabasePopulator(new ByteArrayResource(schema.getBytes(StandardCharsets.UTF_8))).execute(ds);
        var config=new Configuration();config.setMapUnderscoreToCamelCase(true);config.addMapper(AiCallLogMapper.class);
        var factory=new SqlSessionFactoryBean();factory.setDataSource(ds);factory.setConfiguration(config);
        mapper=new SqlSessionTemplate(factory.getObject()).getMapper(AiCallLogMapper.class);
        var json=new ObjectMapper();recorder=new AiCallRecorder(mapper,new AiCostCalculator(AiCostCalculatorTest.prices(),json),json,new DataSourceTransactionManager(ds));
    }
    @AfterAll void cleanup(){if(admin!=null && database!=null && database.matches("videoai_cost_it_[a-f0-9]{32}"))admin.execute("DROP DATABASE IF EXISTS `"+database+"`");}
    String begin(String task,int execution,AiCallContext.Stage stage,int subtask,String model){return recorder.begin(UUID.randomUUID().toString(),new AiCallContext(task,execution,stage,subtask),model);}

    @Test void repeatedConcurrentReceiptCostsOnlyOnceAndConflictsAreRejected() throws Exception {
        var context=new AiCallContext("concurrent",0,AiCallContext.Stage.VIDEO_ANALYSIS,2);
        String id=UUID.randomUUID().toString();var pool=Executors.newFixedThreadPool(4);
        try {
            var starts=new ArrayList<Future<String>>();
            for(int i=0;i<4;i++)starts.add(pool.submit(()->recorder.begin(id,context,"qwen3.7-plus")));
            for(var start:starts)assertEquals(id,start.get(15,TimeUnit.SECONDS));
            var futures=new ArrayList<Future<Boolean>>();
            for(int i=0;i<4;i++)futures.add(pool.submit(()->recorder.finish(id,AiCallRecorder.Outcome.SUCCEEDED,new AiUsage(67995L,1023L,null,"{}"),"req1",null)));
            for(var f:futures)assertTrue(f.get(15,TimeUnit.SECONDS));
        } finally {pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM ai_call_log WHERE call_id=?",Integer.class,id));
        assertEquals(new BigDecimal("0.1441740000"),mapper.find(id).getEstimatedCostCny());
        assertTrue(recorder.finish(id,AiCallRecorder.Outcome.SUCCEEDED,AiUsage.unknown(),null,null));
        assertEquals(new BigDecimal("0.1441740000"),mapper.find(id).getEstimatedCostCny());
        assertThrows(IllegalStateException.class,()->recorder.finish(id,AiCallRecorder.Outcome.SUCCEEDED,new AiUsage(1L,1L,null,null),"req1",null));
        assertThrows(IllegalStateException.class,()->recorder.begin(id,new AiCallContext("other",0,AiCallContext.Stage.VIDEO_ANALYSIS,2),"qwen3.7-plus"));
    }
    @Test void failedUsageAndRetriesRemainSeparateAcrossExecutions() {
        var failed=begin("retry",0,AiCallContext.Stage.VIDEO_ANALYSIS,0,"qwen3.7-plus");
        recorder.finish(failed,AiCallRecorder.Outcome.FAILED,new AiUsage(1000L,100L,null,"{}"),"r1","InternalError");
        var unknown=begin("retry",0,AiCallContext.Stage.VIDEO_ANALYSIS,0,"qwen3.7-plus");
        recorder.finish(unknown,AiCallRecorder.Outcome.UNKNOWN,AiUsage.unknown(),null,"Timeout");
        var next=begin("retry",1,AiCallContext.Stage.VIDEO_ANALYSIS,0,"qwen3.7-plus");
        recorder.finish(next,AiCallRecorder.Outcome.SUCCEEDED,new AiUsage(1000L,100L,null,"{}"),"r3",null);
        assertEquals(new BigDecimal("0.0028000000"),mapper.find(failed).getEstimatedCostCny());
        assertNull(mapper.find(unknown).getEstimatedCostCny());
        assertEquals(2,mapper.summarize("retry").size());
        assertEquals(new BigDecimal("0.0056000000"),jdbc.queryForObject("SELECT SUM(estimated_cost_cny) FROM ai_call_log WHERE task_id='retry'",BigDecimal.class));
    }
    @Test void asrBindingPollCompletionAndCancelledTaskAreIndependent() {
        String id=begin("asr",0,AiCallContext.Stage.ASR,0,"qwen-audio-3.0-asr-flash-filetrans");
        recorder.bindRemoteTask(id,"remote-1");recorder.bindRemoteTask(id,"remote-1");
        assertThrows(IllegalStateException.class,()->recorder.bindRemoteTask(id,"remote-2"));
        assertEquals(1,mapper.findAsr("asr",0,0,"remote-1").size());
        var usage=new AiUsage(null,null,new BigDecimal("393"),"{\"duration\":393}");
        // 没有父任务RUNNING/租约依赖，已登记费用可在失效执行后收尾。
        recorder.finish(id,AiCallRecorder.Outcome.SUCCEEDED,usage,"submit-id",null);
        recorder.finish(id,AiCallRecorder.Outcome.SUCCEEDED,usage,"submit-id",null);
        assertEquals(new BigDecimal("0.0864600000"),mapper.find(id).getEstimatedCostCny());
    }
    @Test void zeroUnknownAndInvalidAreDistinctAndDatabaseRejectsNegativeTokens() {
        String absent=begin("unknown",0,AiCallContext.Stage.TEXT_SCREEN,0,"missing-model");
        recorder.finish(absent,AiCallRecorder.Outcome.SUCCEEDED,new AiUsage(10L,5L,null,"{}"),"req",null);
        assertEquals("MISSING_PRICE",mapper.find(absent).getCostUnknownReason());
        String zero=begin("unknown",0,AiCallContext.Stage.TEXT_SCREEN,1,"qwen3.8-flash");
        recorder.finish(zero,AiCallRecorder.Outcome.NOT_SENT,AiUsage.unknown(),null,null);
        assertEquals(new BigDecimal("0.0000000000"),mapper.find(zero).getEstimatedCostCny());
        String invalid=begin("unknown",0,AiCallContext.Stage.TEXT_SCREEN,2,"qwen3.8-flash");
        recorder.finish(invalid,AiCallRecorder.Outcome.SUCCEEDED,new AiUsage(-10L,1L,null,"{}"),"bad",null);
        assertEquals("INVALID_USAGE",mapper.find(invalid).getCostUnknownReason());
        assertNull(mapper.find(invalid).getInputTokens());
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE ai_call_log SET input_tokens=-1 WHERE call_id=?",zero));
    }
    @Test void lateReceiptEnrichesUnknownButNeverErasesKnownUsage() {
        String id=begin("late",0,AiCallContext.Stage.TEXT_SCREEN,0,"qwen3.8-flash");
        recorder.finish(id,AiCallRecorder.Outcome.UNKNOWN,AiUsage.unknown(),null,"Timeout");
        recorder.finish(id,AiCallRecorder.Outcome.UNKNOWN,new AiUsage(100L,10L,null,"{}"),"request-late",null);
        var amount=mapper.find(id).getEstimatedCostCny();assertNotNull(amount);
        recorder.finish(id,AiCallRecorder.Outcome.SUCCEEDED,AiUsage.unknown(),"request-late",null);
        assertEquals("SUCCEEDED",mapper.find(id).getStatus());assertEquals(amount,mapper.find(id).getEstimatedCostCny());
        assertThrows(IllegalStateException.class,()->recorder.finish(id,AiCallRecorder.Outcome.NOT_SENT,AiUsage.unknown(),null,null));
        String asr=begin("late",0,AiCallContext.Stage.ASR,0,"qwen-audio-3.0-asr-flash-filetrans");recorder.bindRemoteTask(asr,"submitted");
        assertThrows(IllegalArgumentException.class,()->recorder.finish(asr,AiCallRecorder.Outcome.NOT_SENT,AiUsage.unknown(),null,null));
    }
}
