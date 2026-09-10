package com.videoai.worker.scheduler;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ByteArrayResource;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 显式开启；只创建/删除本次随机测试库，不操作配置中的业务库。 */
@EnabledIfSystemProperty(named="dispatch.mysql.acceptance",matches="true")
@Timeout(120)
class DatabaseTaskMysqlAcceptanceTest {
    @Test void freshSchemaCompetingClaimsAndLifecycle() throws Exception {
        assertThrows(ClassNotFoundException.class,()->Class.forName("org.apache.kafka.clients.consumer.KafkaConsumer"));
        Path root=Path.of("..").toAbsolutePath().normalize();
        var env=new StandardEnvironment();
        for(var source:new YamlPropertySourceLoader().load("acceptance",new FileSystemResource(root.resolve("video-worker/src/main/resources/application-dev.yml"))))
            env.getPropertySources().addLast(source);
        String original=env.getRequiredProperty("spring.datasource.url");
        String username=env.getRequiredProperty("spring.datasource.username");
        String password=env.getRequiredProperty("spring.datasource.password");
        String database="videoai_dispatch_it_"+UUID.randomUUID().toString().replace("-","");
        String base=original.substring(0,original.indexOf('/',"jdbc:mysql://".length())+1);
        String options="?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&connectTimeout=5000&socketTimeout=10000";
        boolean created=false;
        try(var admin=DriverManager.getConnection(base+options,username,password);var command=admin.createStatement()) {
            try {
                command.execute("CREATE DATABASE `"+database+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");created=true;
                var source=new DriverManagerDataSource(base+database+options,username,password);
                String schema=Files.readString(root.resolve("sql/schema.sql"))
                        .replaceAll("(?im)^CREATE DATABASE[^;]*;","").replaceAll("(?im)^USE [^;]*;","");
                assertFalse(schema.contains("video_ai."));
                new ResourceDatabasePopulator(new ByteArrayResource(schema.getBytes(StandardCharsets.UTF_8))).execute(source);
                var jdbc=new JdbcTemplate(source);
                var repository=new TaskDispatchRepository(source,new DataSourceTransactionManager(source),90);
                jdbc.update("INSERT INTO analysis_task(task_id,upload_id,video_url,user_id,status,attempt_no) VALUES ('race','test-upload','test.mp4',1,'PENDING',0)");
                var pool=Executors.newFixedThreadPool(8);var gate=new CountDownLatch(1);
                var leases=new ArrayList<TaskDispatchRepository.Lease>();
                try {
                    var futures=new ArrayList<Future<TaskDispatchRepository.Lease>>();
                    for(int i=0;i<8;i++) futures.add(pool.submit(()->{gate.await();return repository.claim(new TaskDispatchRepository.Candidate("race",0));}));
                    gate.countDown();for(var f:futures){var lease=f.get();if(lease!=null)leases.add(lease);}
                } finally {pool.shutdownNow();assertTrue(pool.awaitTermination(3,TimeUnit.SECONDS));}
                assertEquals(1,leases.size());var lease=leases.get(0);assertTrue(repository.renew(lease));
                assertThrows(IllegalStateException.class,()->repository.fenced(lease,()->{
                    jdbc.update("UPDATE analysis_task SET current_step='ROLLBACK' WHERE task_id='race'");
                    throw new IllegalStateException("rollback");
                }));
                assertNull(jdbc.queryForObject("SELECT current_step FROM analysis_task WHERE task_id='race'",String.class));
                jdbc.update("UPDATE analysis_task SET lease_until=TIMESTAMPADD(SECOND,-1,NOW(3)) WHERE task_id='race'");
                assertFalse(repository.renew(lease));assertEquals(1,repository.failExpired(12));
                assertFalse(repository.finish(lease,"SUCCEEDED",null));
                assertEquals("FAILED",jdbc.queryForObject("SELECT status FROM analysis_task WHERE task_id='race'",String.class));
                jdbc.update("INSERT INTO analysis_task(task_id,upload_id,video_url,user_id,status,attempt_no) VALUES ('complete','test-upload','test.mp4',1,'PENDING',0)");
                var completed=new CountDownLatch(1);
                try(var scheduler=new DatabaseTaskScheduler(repository,context->{context.check();completed.countDown();return DatabaseTaskScheduler.Outcome.succeeded();},3)) {
                    scheduler.scanOnce();assertTrue(completed.await(3,TimeUnit.SECONDS));
                    long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                    while(scheduler.stats().available()!=3&&System.nanoTime()<end)Thread.sleep(20);
                    assertEquals(3,scheduler.stats().available());
                    assertEquals("SUCCEEDED",jdbc.queryForObject("SELECT status FROM analysis_task WHERE task_id='complete'",String.class));
                }
                assertTrue(repository.candidates(12).isEmpty());
                try(var application=new org.springframework.boot.builder.SpringApplicationBuilder(com.videoai.worker.VideoWorkerApplication.class)
                        .web(org.springframework.boot.WebApplicationType.NONE)
                        .run("--spring.profiles.active=dev", "--spring.datasource.url="+base+database+options,
                                "--spring.datasource.username="+username,"--spring.datasource.password="+password,
                                "--spring.main.banner-mode=off","--logging.level.root=WARN")) {
                    assertNotNull(application.getBean(DatabaseTaskScheduler.class));
                    assertNotNull(application.getBean(com.videoai.worker.processor.VideoAnalysisService.class));
                    assertFalse(application.containsBean("taskOutboxService"));
                    assertFalse(application.containsBean("taskConsumer"));
                    assertEquals(3,application.getBean(DatabaseTaskScheduler.class).stats().available());
                }
                System.out.println("MYSQL_ACCEPTANCE_OK schema=true contenders=8 winners=1 expiredFailed=true lifecycle=true kafkaAbsent=true");
            } finally {
                if(created) {
                    if(!database.matches("videoai_dispatch_it_[a-f0-9]{32}"))throw new IllegalStateException("测试库名称校验失败");
                    command.execute("DROP DATABASE `"+database+"`");
                }
            }
        } catch(SQLException e) {
            // 避免驱动错误输出连接地址或凭据。
            throw new IllegalStateException("MySQL验收失败 SQLState="+e.getSQLState()+" code="+e.getErrorCode());
        }
    }
}
