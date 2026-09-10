package com.videoai.api.service;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.*;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="dispatch.mysql.acceptance",matches="true")
@Timeout(120)
class DatabaseApiMysqlAcceptanceTest {
    @Test void bootsWithoutKafkaAndUsesFreshTaskSchema() throws Exception {
        assertThrows(ClassNotFoundException.class,()->Class.forName("org.apache.kafka.clients.consumer.KafkaConsumer"));
        Path root=Path.of("..").toAbsolutePath().normalize();var env=new StandardEnvironment();
        for(var config:new YamlPropertySourceLoader().load("acceptance",new FileSystemResource(root.resolve("video-api/src/main/resources/application-dev.yml"))))env.getPropertySources().addLast(config);
        String url=env.getRequiredProperty("spring.datasource.url"),user=env.getRequiredProperty("spring.datasource.username"),pass=env.getRequiredProperty("spring.datasource.password");
        String base=url.substring(0,url.indexOf('/',"jdbc:mysql://".length())+1);
        String database="videoai_api_it_"+UUID.randomUUID().toString().replace("-","");
        String options="?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&connectTimeout=5000&socketTimeout=10000";
        boolean created=false;
        try(var admin=DriverManager.getConnection(base+options,user,pass);var command=admin.createStatement()) {
            try {
                command.execute("CREATE DATABASE `"+database+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");created=true;
                var source=new DriverManagerDataSource(base+database+options,user,pass);
                String schema=Files.readString(root.resolve("sql/schema.sql")).replaceAll("(?im)^CREATE DATABASE[^;]*;","").replaceAll("(?im)^USE [^;]*;","");
                assertFalse(schema.contains("video_ai."));
                new ResourceDatabasePopulator(new ByteArrayResource(schema.getBytes(StandardCharsets.UTF_8))).execute(source);
                var jdbc=new JdbcTemplate(source);
                jdbc.update("INSERT INTO analysis_task(task_id,upload_id,video_url,user_id,status,attempt_no) VALUES ('api-task','test-upload','test.mp4',1,'FAILED',0)");
                try(var app=new org.springframework.boot.builder.SpringApplicationBuilder(com.videoai.api.VideoApiApplication.class)
                        .run("--spring.profiles.active=dev","--server.port=0","--spring.datasource.url="+base+database+options,
                                "--spring.datasource.username="+user,"--spring.datasource.password="+pass,
                                "--spring.main.banner-mode=off","--logging.level.root=WARN")) {
                    var service=app.getBean(TaskService.class);
                    assertEquals("FAILED",service.getTask("api-task").getStatus());
                    var retried=service.retryTask("api-task",1L);
                    assertEquals("PENDING",retried.getStatus());assertEquals(1,retried.getAttemptNo());
                    service.deleteTask("api-task",1L);assertEquals("CANCELLED",service.getTask("api-task").getStatus());
                    assertFalse(app.containsBean("taskOutboxService"));
                    int port=Integer.parseInt(app.getEnvironment().getRequiredProperty("local.server.port"));
                    var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:"+port+"/api/tasks/api-task")).GET().build();
                    var response=java.net.http.HttpClient.newHttpClient().send(request,java.net.http.HttpResponse.BodyHandlers.ofString());
                    assertTrue(response.statusCode()<500,"HTTP入口应正常响应");
                }
                System.out.println("API_MYSQL_ACCEPTANCE_OK boot=true query=true retry=true cancel=true kafkaAbsent=true");
            } finally {
                if(created){if(!database.matches("videoai_api_it_[a-f0-9]{32}"))throw new IllegalStateException("测试库校验失败");command.execute("DROP DATABASE `"+database+"`");}
            }
        } catch(SQLException e){throw new IllegalStateException("API MySQL验收失败 SQLState="+e.getSQLState()+" code="+e.getErrorCode());}
    }
}
