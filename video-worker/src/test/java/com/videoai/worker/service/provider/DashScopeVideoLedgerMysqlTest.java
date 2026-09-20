package com.videoai.worker.service.provider;

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

/** Mock HTTP + 隔离 MySQL 验证实际视频客户端记账，不调用付费接口。 */
@EnabledIfSystemProperty(named="cost.mysql.acceptance",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(90)
class DashScopeVideoLedgerMysqlTest {
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
        database="videoai_video_cost_it_"+UUID.randomUUID().toString().replace("-","");
        admin.execute("CREATE DATABASE `"+database+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        var ds=new DriverManagerDataSource(base+database+options,user,pass);jdbc=new JdbcTemplate(ds);
        String schema=Files.readString(root.resolve("sql/schema.sql")).replaceAll("(?im)^CREATE DATABASE[^;]*;","").replaceAll("(?im)^USE [^;]*;","");
        new ResourceDatabasePopulator(new ByteArrayResource(schema.getBytes(StandardCharsets.UTF_8))).execute(ds);
        var config=new Configuration();config.setMapUnderscoreToCamelCase(true);config.addMapper(AiCallLogMapper.class);
        var factory=new SqlSessionFactoryBean();factory.setDataSource(ds);factory.setConfiguration(config);
        mapper=new SqlSessionTemplate(factory.getObject()).getMapper(AiCallLogMapper.class);
        var json=new ObjectMapper();recorder=new AiCallRecorder(mapper,new AiCostCalculator(prices(),json),json,new DataSourceTransactionManager(ds));
    }
    @AfterAll void cleanup(){if(admin!=null && database!=null && database.matches("videoai_video_cost_it_[a-f0-9]{32}"))admin.execute("DROP DATABASE IF EXISTS `"+database+"`");}

    private static AiPricingProperties prices() throws Exception {
        var properties = org.springframework.core.io.support.PropertiesLoaderUtils.loadProperties(new ClassPathResource("ai-pricing.properties"));
        var source = new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(properties);
        return new org.springframework.boot.context.properties.bind.Binder(source)
                .bind("ai.pricing", AiPricingProperties.class).get();
    }

    @Test void concurrentSegmentsAndRetryKeepSeparateAttemptsAndCosts() throws Exception {
        var attempts=new java.util.concurrent.ConcurrentHashMap<String,java.util.concurrent.atomic.AtomicInteger>();
        var json=new ObjectMapper();
        var barrier=new CyclicBarrier(2);
        var http=new OkHttpClient.Builder().addInterceptor(chain->{
            var buffer=new okio.Buffer();chain.request().body().writeTo(buffer);
            var payload=json.readTree(buffer.readUtf8());
            String prompt=payload.path("messages").path(0).path("content").path(1).path("text").asText();
            int no=attempts.computeIfAbsent(prompt,k->new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();
            if(no==1) try {barrier.await(5,TimeUnit.SECONDS);}catch(Exception e){throw new java.io.IOException(e);}
            boolean failure=prompt.equals("retry") && no==1;
            String body=failure ? "{\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":0},\"id\":\"failed-1\"}"
                    : "{\"id\":\"success-"+prompt+"\",\"usage\":{\"prompt_tokens\":1000,\"completion_tokens\":100},\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"ok\"}}]}";
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(failure?503:200).message("mock")
                    .body(ResponseBody.create(MediaType.parse("application/json"),body)).build();
        }).build();
        var config=new DashScopeConfig();config.setApiKey("test-key");config.setModel("qwen3.7-plus");
        var provider=new DashScopeVideoProvider(config,recorder,json,http);
        var retryContext=new AiCallContext("video-a",1,AiCallContext.Stage.VIDEO_ANALYSIS,3);
        var otherContext=new AiCallContext("video-b",2,AiCallContext.Stage.VIDEO_ANALYSIS,1);
        var pool=Executors.newFixedThreadPool(2);
        try {
            var retry=pool.submit(()->{
                var e=assertThrows(AiProviderException.class,()->provider.callDetailed(retryContext,"https://example.com/a","retry"));
                assertTrue(e.isRetryable());
                return provider.callDetailed(retryContext,"https://example.com/a","retry");
            });
            var other=pool.submit(()->provider.callDetailed(otherContext,"https://example.com/b","other"));
            assertEquals("ok",retry.get(20,TimeUnit.SECONDS).text());assertEquals("ok",other.get(20,TimeUnit.SECONDS).text());
        } finally {pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM ai_call_log",Integer.class));
        assertEquals(2,attempts.get("retry").get());assertEquals(1,attempts.get("other").get());
        var rows=jdbc.queryForList("SELECT call_id FROM ai_call_log WHERE task_id='video-a'",String.class);
        assertEquals(2,new HashSet<>(rows).size());
        for(String id:rows) {var row=mapper.find(id);assertEquals(1,row.getExecutionNo());assertEquals(3,row.getSubtaskNo());}
        assertEquals(new BigDecimal("0.0030000000"),jdbc.queryForObject("SELECT SUM(estimated_cost_cny) FROM ai_call_log WHERE task_id='video-a'",BigDecimal.class));
        assertEquals(new BigDecimal("0.0028000000"),jdbc.queryForObject("SELECT SUM(estimated_cost_cny) FROM ai_call_log WHERE task_id='video-b'",BigDecimal.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM ai_call_log WHERE status='FAILED'",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM ai_call_log WHERE estimated_cost_cny IS NULL",Integer.class));
    }
}
