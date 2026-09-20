package com.videoai.api.service;

import com.videoai.infra.cost.*;
import com.videoai.common.domain.AnalysisTask;
import static org.mockito.Mockito.*;

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

/** 隔离 MySQL 验证费用汇总、执行覆盖标记和复用片段，不调用付费接口。 */
@EnabledIfSystemProperty(named="cost.mysql.acceptance",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(90)
class TaskCostMysqlTest {
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
        database="videoai_cost_api_it_"+UUID.randomUUID().toString().replace("-","");
        admin.execute("CREATE DATABASE `"+database+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        var ds=new DriverManagerDataSource(base+database+options,user,pass);jdbc=new JdbcTemplate(ds);
        String schema=Files.readString(root.resolve("sql/schema.sql")).replaceAll("(?im)^CREATE DATABASE[^;]*;","").replaceAll("(?im)^USE [^;]*;","");
        new ResourceDatabasePopulator(new ByteArrayResource(schema.getBytes(StandardCharsets.UTF_8))).execute(ds);
        var config=new Configuration();config.setMapUnderscoreToCamelCase(true);config.addMapper(AiCallLogMapper.class);
        var factory=new SqlSessionFactoryBean();factory.setDataSource(ds);factory.setConfiguration(config);
        mapper=new SqlSessionTemplate(factory.getObject()).getMapper(AiCallLogMapper.class);
        var json=new ObjectMapper();recorder=new AiCallRecorder(mapper,new AiCostCalculator(prices(),json),json,new DataSourceTransactionManager(ds));
    }
    @AfterAll void cleanup(){if(admin!=null && database!=null && database.matches("videoai_cost_api_it_[a-f0-9]{32}"))admin.execute("DROP DATABASE IF EXISTS `"+database+"`");}

    private static AiPricingProperties prices() throws Exception {
        var properties = org.springframework.core.io.support.PropertiesLoaderUtils.loadProperties(new ClassPathResource("ai-pricing.properties"));
        var source = new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(properties);
        return new org.springframework.boot.context.properties.bind.Binder(source)
                .bind("ai.pricing", AiPricingProperties.class).get();
    }

    @Test void aggregateAndCoverageUseRealSchemaWithoutJoiningAndMultiplyingCosts() {
        jdbc.update("INSERT INTO analysis_task(task_id,upload_id,user_id,video_url,status,attempt_no) VALUES('cost-task','upload',7,'private/video','SUCCEEDED',1)");
        for(int no=0;no<2;no++)jdbc.update("INSERT INTO analysis_execution(task_id,execution_no,analysis_mode,config_snapshot,config_hash,input_hash) VALUES('cost-task',?,'AUDIO_PREFILTER',?,REPEAT('a',64),REPEAT('b',64))",no,no==0?"{}":"{\"costLedgerVersion\":1}");
        jdbc.update("INSERT INTO analysis_segment(task_id,execution_no,segment_no,start_ms,end_ms,object_key,status,result) VALUES('cost-task',0,0,0,1000,'private/old','SUCCEEDED','{}')");
        jdbc.update("INSERT INTO analysis_segment(task_id,execution_no,segment_no,start_ms,end_ms,object_key,reused_execution_no,reused_segment_no,status,result) VALUES('cost-task',1,0,0,1000,'private/new',0,0,'SUCCEEDED','{}')");
        for(int no=0;no<2;no++) {
            String id=recorder.begin(UUID.randomUUID().toString(),new AiCallContext("cost-task",no,AiCallContext.Stage.VIDEO_ANALYSIS,1),"qwen3.7-plus");
            recorder.finish(id,AiCallRecorder.Outcome.FAILED,new AiUsage(1000L,100L,null,"{}"),null,"MODEL_ERROR");
        }
        String unknown=recorder.begin(UUID.randomUUID().toString(),new AiCallContext("cost-task",1,AiCallContext.Stage.VIDEO_ANALYSIS,1),"qwen3.7-plus");
        recorder.finish(unknown,AiCallRecorder.Outcome.UNKNOWN,AiUsage.unknown(),null,"TIMEOUT");
        var owner=mock(TaskSegmentService.class);var task=new AnalysisTask();task.setAttemptNo(1);task.setStatus("SUCCEEDED");
        when(owner.ownedTask("cost-task",7L)).thenReturn(task);
        var result=new TaskCostService(owner,mapper).get("cost-task",7L);
        assertEquals("0.0028000000",result.current().knownCostCny());assertEquals("0.0056000000",result.lifetime().knownCostCny());
        assertEquals(2,result.current().callCount());assertEquals(3,result.lifetime().callCount());assertEquals(1,result.current().incompleteCount());
        assertTrue(result.current().coverageKnown());assertFalse(result.lifetime().coverageKnown());
        assertEquals(2,result.segments().size());assertTrue(result.segments().get(0).reused());
        assertEquals("0.0000000000",result.segments().get(0).current().knownCostCny());
        assertEquals("1000",result.current().inputTokens());
    }
}
