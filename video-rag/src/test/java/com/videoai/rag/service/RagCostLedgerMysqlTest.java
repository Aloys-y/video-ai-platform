package com.videoai.rag.service;

import com.videoai.infra.cost.*;
import com.videoai.infra.rag.config.*;
import com.videoai.infra.rag.vector.DashScopeEmbeddingProvider;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import com.videoai.common.rag.RetrievalHit;

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

/** 本地 HTTP + 隔离 MySQL 验证查询向量和重排记账，不调用付费接口。 */
@EnabledIfSystemProperty(named="cost.mysql.acceptance",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(90)
class RagCostLedgerMysqlTest {
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
        database="videoai_rag_cost_it_"+UUID.randomUUID().toString().replace("-","");
        admin.execute("CREATE DATABASE `"+database+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        var ds=new DriverManagerDataSource(base+database+options,user,pass);jdbc=new JdbcTemplate(ds);
        String schema=Files.readString(root.resolve("sql/schema.sql")).replaceAll("(?im)^CREATE DATABASE[^;]*;","").replaceAll("(?im)^USE [^;]*;","");
        new ResourceDatabasePopulator(new ByteArrayResource(schema.getBytes(StandardCharsets.UTF_8))).execute(ds);
        var config=new Configuration();config.setMapUnderscoreToCamelCase(true);config.addMapper(AiCallLogMapper.class);
        var factory=new SqlSessionFactoryBean();factory.setDataSource(ds);factory.setConfiguration(config);
        mapper=new SqlSessionTemplate(factory.getObject()).getMapper(AiCallLogMapper.class);
        var json=new ObjectMapper();recorder=new AiCallRecorder(mapper,new AiCostCalculator(prices(),json),json,new DataSourceTransactionManager(ds));
    }
    @AfterAll void cleanup(){if(admin!=null && database!=null && database.matches("videoai_rag_cost_it_[a-f0-9]{32}"))admin.execute("DROP DATABASE IF EXISTS `"+database+"`");}

    private static AiPricingProperties prices() throws Exception {
        var properties = org.springframework.core.io.support.PropertiesLoaderUtils.loadProperties(new ClassPathResource("ai-pricing.properties"));
        var source = new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(properties);
        return new org.springframework.boot.context.properties.bind.Binder(source)
                .bind("ai.pricing", AiPricingProperties.class).get();
    }

    @Test void concurrentQueriesKeepEmbeddingAndRerankUnderTheirVideo() throws Exception {
        var requests=new java.util.concurrent.atomic.AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            exchange.getRequestBody().readAllBytes();requests.incrementAndGet();
            String response=exchange.getRequestURI().getPath().endsWith("/rerank")
                    ? "{\"usage\":{\"total_tokens\":2000},\"results\":[{\"index\":0,\"relevance_score\":0.9}]}"
                    : "{\"usage\":{\"total_tokens\":1000},\"output\":{\"embeddings\":[{\"embedding\":[0.1,0.2]}]}}";
            byte[] body=response.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,body.length);
            exchange.getResponseBody().write(body);exchange.close();
        });
        server.start();
        var embeddingConfig=new OpenAiEmbeddingProperties();embeddingConfig.setApiKey("test-key");
        embeddingConfig.setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort());
        var ragConfig=new RagProperties();ragConfig.setRerankBaseUrl(embeddingConfig.getBaseUrl()+"/rerank");
        var embedding=new DashScopeEmbeddingProvider(embeddingConfig,new ObjectMapper(),recorder);
        var reranker=new RerankService(ragConfig,embeddingConfig,new ObjectMapper(),recorder);
        var candidates=List.of(RetrievalHit.builder().title("test").headingPath("section").contentText("text").build());
        var pool=Executors.newFixedThreadPool(2);
        try {
            var jobs=new ArrayList<Future<?>>();
            for(int i=0;i<2;i++) {
                int no=i;
                jobs.add(pool.submit(()->{
                    assertEquals(2,embedding.embedQuery(new AiCallContext("rag-video-"+no,no,AiCallContext.Stage.RAG_EMBEDDING,0),"q").size());
                    assertEquals(1,reranker.rerank(new AiCallContext("rag-video-"+no,no,AiCallContext.Stage.RAG_RERANK,0),"q",candidates).size());
                }));
            }
            for(var job:jobs)job.get(20,TimeUnit.SECONDS);
        }finally{pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));server.stop(0);}
        assertEquals(4,requests.get());
        for(int i=0;i<2;i++) {
            String task="rag-video-"+i;
            assertEquals(2,mapper.summarize(task).size());
            assertEquals(new BigDecimal("0.0015000000"),jdbc.queryForObject("SELECT SUM(estimated_cost_cny) FROM ai_call_log WHERE task_id=?",BigDecimal.class,task));
            for(var id:jdbc.queryForList("SELECT call_id FROM ai_call_log WHERE task_id=?",String.class,task)) {
                var row=mapper.find(id);assertEquals(i,row.getExecutionNo());assertEquals(0,row.getSubtaskNo());
                assertEquals("SUCCEEDED",row.getStatus());assertNull(row.getOutputTokens());assertNull(row.getCostUnknownReason());
            }
        }
    }
}
