package com.videoai.worker.asr;

import com.videoai.infra.cost.*;
import com.videoai.worker.config.DashScopeConfig;
import okhttp3.*;

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

/** Mock HTTP + 隔离 MySQL 验证实际ASR客户端记账，不调用付费接口。 */
@EnabledIfSystemProperty(named="cost.mysql.acceptance",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(90)
class DashScopeAsrLedgerMysqlTest {
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
        database="videoai_asr_cost_it_"+UUID.randomUUID().toString().replace("-","");
        admin.execute("CREATE DATABASE `"+database+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        var ds=new DriverManagerDataSource(base+database+options,user,pass);jdbc=new JdbcTemplate(ds);
        String schema=Files.readString(root.resolve("sql/schema.sql")).replaceAll("(?im)^CREATE DATABASE[^;]*;","").replaceAll("(?im)^USE [^;]*;","");
        new ResourceDatabasePopulator(new ByteArrayResource(schema.getBytes(StandardCharsets.UTF_8))).execute(ds);
        var config=new Configuration();config.setMapUnderscoreToCamelCase(true);config.addMapper(AiCallLogMapper.class);
        var factory=new SqlSessionFactoryBean();factory.setDataSource(ds);factory.setConfiguration(config);
        mapper=new SqlSessionTemplate(factory.getObject()).getMapper(AiCallLogMapper.class);
        var json=new ObjectMapper();recorder=new AiCallRecorder(mapper,new AiCostCalculator(prices(),json),json,new DataSourceTransactionManager(ds));
    }
    @AfterAll void cleanup(){if(admin!=null && database!=null && database.matches("videoai_asr_cost_it_[a-f0-9]{32}"))admin.execute("DROP DATABASE IF EXISTS `"+database+"`");}

    private static AiPricingProperties prices() throws Exception {
        var properties = org.springframework.core.io.support.PropertiesLoaderUtils.loadProperties(new ClassPathResource("ai-pricing.properties"));
        var source = new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(properties);
        return new org.springframework.boot.context.properties.bind.Binder(source)
                .bind("ai.pricing", AiPricingProperties.class).get();
    }

    @Test void repeatedPollAndLaterExecutionReuseChargeOriginalSubmissionOnce() throws Exception {
        var posts=new java.util.concurrent.atomic.AtomicInteger();var polls=new java.util.concurrent.atomic.AtomicInteger();
        var http=new OkHttpClient.Builder().addInterceptor(chain->{
            String body;
            if(chain.request().method().equals("POST")) {
                posts.incrementAndGet();
                assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM ai_call_log WHERE status='RUNNING'",Integer.class));
                body="{\"request_id\":\"submit-id\",\"output\":{\"task_id\":\"remote-original\"}}";
            } else {
                int n=polls.incrementAndGet();
                body=n==1 ? "{\"output\":{\"task_status\":\"RUNNING\"}}"
                        : "{\"request_id\":\"poll-"+n+"\",\"usage\":{\"duration\":393},\"output\":{\"task_status\":\"SUCCEEDED\",\"results\":[{\"subtask_status\":\"SUCCEEDED\",\"transcription_url\":\"https://result.example/audio.json\"}]}}";
            }
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("mock")
                    .body(ResponseBody.create(MediaType.parse("application/json"),body)).build();
        }).build();
        var config=new AsrProperties();config.setApiKey("test-key");config.setModel("qwen-audio-3.0-asr-flash-filetrans");
        var client=new DashScopeAsrClient(config,new DashScopeConfig(),new ObjectMapper(),recorder,http);
        var original=new AiCallContext("asr-task",0,AiCallContext.Stage.ASR,1);
        var later=new AiCallContext("asr-task",2,AiCallContext.Stage.ASR,1);
        String remote=client.submit(original,"https://audio.example/audio.wav");
        String id=recorder.findAsrCall(original,remote);
        assertEquals("RUNNING",mapper.find(id).getStatus());assertNull(mapper.find(id).getEstimatedCostCny());
        assertEquals("RUNNING",client.query(original,remote).status());
        assertEquals("SUCCEEDED",client.query(later,remote).status());
        assertEquals("SUCCEEDED",client.query(later,remote).status());
        assertEquals(1,posts.get());assertEquals(3,polls.get());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM ai_call_log",Integer.class));
        var row=mapper.find(id);assertEquals(0,row.getExecutionNo());assertEquals("submit-id",row.getRequestId());
        assertEquals(new BigDecimal("0.0864600000"),row.getEstimatedCostCny());assertEquals(new BigDecimal("393.000"),row.getAudioSeconds());
        assertThrows(IllegalStateException.class,()->recorder.findAsrCall(new AiCallContext("other-task",2,AiCallContext.Stage.ASR,1),remote));
        assertThrows(IllegalStateException.class,()->recorder.findAsrCall(new AiCallContext("asr-task",2,AiCallContext.Stage.ASR,2),remote));
    }
}
