package com.videoai.worker.screening;

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

/** Mock HTTP + 隔离 MySQL 验证实际文本客户端记账，不调用付费接口。 */
@EnabledIfSystemProperty(named="cost.mysql.acceptance",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(90)
class DashScopeTextLedgerMysqlTest {
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
        database="videoai_text_cost_it_"+UUID.randomUUID().toString().replace("-","");
        admin.execute("CREATE DATABASE `"+database+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        var ds=new DriverManagerDataSource(base+database+options,user,pass);jdbc=new JdbcTemplate(ds);
        String schema=Files.readString(root.resolve("sql/schema.sql")).replaceAll("(?im)^CREATE DATABASE[^;]*;","").replaceAll("(?im)^USE [^;]*;","");
        new ResourceDatabasePopulator(new ByteArrayResource(schema.getBytes(StandardCharsets.UTF_8))).execute(ds);
        var config=new Configuration();config.setMapUnderscoreToCamelCase(true);config.addMapper(AiCallLogMapper.class);
        var factory=new SqlSessionFactoryBean();factory.setDataSource(ds);factory.setConfiguration(config);
        mapper=new SqlSessionTemplate(factory.getObject()).getMapper(AiCallLogMapper.class);
        var json=new ObjectMapper();recorder=new AiCallRecorder(mapper,new AiCostCalculator(prices(),json),json,new DataSourceTransactionManager(ds));
    }
    @AfterAll void cleanup(){if(admin!=null && database!=null && database.matches("videoai_text_cost_it_[a-f0-9]{32}"))admin.execute("DROP DATABASE IF EXISTS `"+database+"`");}

    private static AiPricingProperties prices() throws Exception {
        var properties = org.springframework.core.io.support.PropertiesLoaderUtils.loadProperties(new ClassPathResource("ai-pricing.properties"));
        var source = new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(properties);
        return new org.springframework.boot.context.properties.bind.Binder(source)
                .bind("ai.pricing", AiPricingProperties.class).get();
    }

    @Test void concurrentHttpReceiptsPersistUnderTheirOwnTaskAndExecution() throws Exception {
        var config = new TextAnalysisProperties();config.setApiKey("test-key");config.setModel("qwen3.8-flash");
        var counter = new java.util.concurrent.atomic.AtomicInteger();
        var http = new OkHttpClient.Builder().addInterceptor(chain->{
            counter.incrementAndGet();
            // 在收到 HTTP 请求前，独立事务已经使 RUNNING 行对另一个连接可见。
            assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM ai_call_log WHERE status='RUNNING'",Integer.class)>0);
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("mock")
                    .body(ResponseBody.create(MediaType.parse("application/json"),
                            "{\"usage\":{\"prompt_tokens\":1000,\"completion_tokens\":100},\"choices\":[]}")).build();
        }).build();
        var client = new DashScopeTextClient(config,new DashScopeConfig(),new ObjectMapper(),recorder,http);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var jobs = new ArrayList<Future<String>>();
            for(int i=0;i<2;i++) {
                var context = new AiCallContext("http-task-"+i,i,AiCallContext.Stage.TEXT_SCREEN,i+1);
                jobs.add(pool.submit(()->client.complete(context,"system","text")));
            }
            for(var job:jobs) assertTrue(job.get(20,TimeUnit.SECONDS).contains("choices"));
        } finally { pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS)); }
        assertEquals(2,counter.get());
        for(int i=0;i<2;i++) {
            var rows=mapper.summarize("http-task-"+i);assertEquals(1,rows.size());
            String id=jdbc.queryForObject("SELECT call_id FROM ai_call_log WHERE task_id=?",String.class,"http-task-"+i);
            var row=mapper.find(id);assertEquals(i,row.getExecutionNo());assertEquals(i+1,row.getSubtaskNo());
            assertEquals("SUCCEEDED",row.getStatus());assertEquals("TEXT_SCREEN",row.getStage());
            assertEquals(new BigDecimal("0.0010700000"),row.getEstimatedCostCny());
            assertNull(row.getCostUnknownReason());assertNotNull(row.getPriceSnapshot());
        }
    }
}
