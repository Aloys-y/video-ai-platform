package com.videoai.api.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoai.common.domain.AnalysisFinishedEvent;
import com.videoai.infra.notification.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.*;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MockMailChainTest {
    JdbcTemplate jdbc;
    AnalysisFinishedEvent event=AnalysisFinishedEvent.of("test-task",0,"SUCCEEDED");
    @BeforeEach void setup() throws Exception {
        var source=new JdbcDataSource();source.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc=new JdbcTemplate(source);
        Path sql=Path.of("../sql/V2.2__mock_mail_notification.sql");
        jdbc.execute(Files.readString(sql));
    }
    @AfterEach void close() {jdbc.execute("DROP ALL OBJECTS");}
    String status() {return jdbc.queryForObject("SELECT status FROM mock_mail_notification",String.class);}
    @Test void duplicateEventAndNewExecution() {
        var calls=new AtomicInteger();var mail=new MockMailService(jdbc,(id,s,b)->{calls.incrementAndGet();return MockMailService.Outcome.SUCCESS;});
        mail.accept(event);mail.accept(event);mail.tick();mail.accept(event);mail.tick();
        assertEquals(1,calls.get());assertEquals("MOCK_SENT",status());
        mail.accept(AnalysisFinishedEvent.of("test-task",1,"FAILED"));mail.tick();assertEquals(2,calls.get());
        assertEquals(2,mail.list("test-task").size());
    }
    @Test void featureDisabledRequiresNeitherDatabaseNorKafka() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
            .withUserConfiguration(MockMailConfig.class,NotificationKafkaConfig.class)
            .run(context->{
                assertNull(context.getStartupFailure());
                assertTrue(context.getBeansOfType(MockMailService.class).isEmpty());
                assertTrue(context.getBeansOfType(NotificationEventPublisher.class).isEmpty());
            });
    }
    @Test void temporaryFailureRetriesAtMostThreeTimes() {
        var mail=new MockMailService(jdbc,(id,s,b)->MockMailService.Outcome.TRANSIENT_FAILURE);mail.accept(event);
        for(int i=0;i<3;i++) {jdbc.update("UPDATE mock_mail_notification SET next_attempt_at=CURRENT_TIMESTAMP");mail.tick();}
        assertEquals("FAILED",status());mail.tick();
        assertEquals(3,jdbc.queryForObject("SELECT attempt_count FROM mock_mail_notification",Integer.class));
    }
    @Test void permanentFailureAndAmbiguousResultAreNotRetried() {
        for(var outcome:List.of(MockMailService.Outcome.PERMANENT_FAILURE,MockMailService.Outcome.UNKNOWN)) {
            jdbc.update("DELETE FROM mock_mail_notification");var calls=new AtomicInteger();
            var mail=new MockMailService(jdbc,(id,s,b)->{calls.incrementAndGet();return outcome;});
            mail.accept(event);mail.tick();mail.tick();assertEquals(1,calls.get());
            assertEquals(outcome==MockMailService.Outcome.UNKNOWN?"UNKNOWN":"FAILED",status());
        }
    }
    @Test void expiredClaimDoesNotBlindlyResend() {
        var calls=new AtomicInteger();var mail=new MockMailService(jdbc,(id,s,b)->{calls.incrementAndGet();return MockMailService.Outcome.SUCCESS;});
        mail.accept(event);jdbc.update("UPDATE mock_mail_notification SET status='SENDING',claim_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP)");
        mail.tick();assertEquals("UNKNOWN",status());assertEquals(0,calls.get());
    }
    @Test void invalidEventRejectedAndOwnershipChecked() {
        var mail=new MockMailService(jdbc,(id,s,b)->MockMailService.Outcome.SUCCESS);
        assertThrows(IllegalArgumentException.class,()->mail.accept(AnalysisFinishedEvent.of("test-task",0,"CANCELLED")));
        var tasks=mock(com.videoai.api.service.TaskSegmentService.class);
        doThrow(new IllegalArgumentException("not owned")).when(tasks).ownedTask(anyString(),any());
        var controller=new MockMailController(tasks,mail);
        assertThrows(IllegalArgumentException.class,()->controller.list("test-task"));
    }
    @Test void realKafkaToDatabaseToMockProvider() throws Exception {
        String topic="mock-chain";
        var broker=new EmbeddedKafkaKraftBroker(1,1,topic);broker.afterPropertiesSet();
        var config=new MockMailConfig();var mail=config.mockMailService(jdbc);var mapper=new ObjectMapper();
        var listener=config.mockMailListener(mail,mapper,broker.getBrokersAsString(),topic,"test-group");
        var factory=new DefaultKafkaProducerFactory<String,String>(Map.of("bootstrap.servers",broker.getBrokersAsString(),
                "key.serializer",org.apache.kafka.common.serialization.StringSerializer.class,"value.serializer",org.apache.kafka.common.serialization.StringSerializer.class));
        var publisher=new NotificationEventPublisher(new KafkaTemplate<>(factory),mapper,topic);
        try {
            listener.start();publisher.publish(event);publisher.publish(event);
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
            while(mail.list(event.taskId()).isEmpty() && System.nanoTime()<deadline) Thread.sleep(100);
            assertEquals(1,mail.list(event.taskId()).size());
            mail.tick();assertEquals("MOCK_SENT",status());
        } finally {publisher.close();listener.stop();factory.destroy();broker.destroy();}
    }
}
