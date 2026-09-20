package com.videoai.api.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoai.common.domain.AnalysisFinishedEvent;
import com.videoai.infra.notification.MockMailService;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.*;
import org.springframework.util.backoff.FixedBackOff;
import java.util.Map;
import java.util.concurrent.*;

@Configuration
@ConditionalOnProperty(name="notification.mock.enabled",havingValue="true")
public class MockMailConfig {
    @Bean MockMailService mockMailService(JdbcTemplate jdbc) {
        // 唯一运行时 Provider 为 Mock；无收件地址、SMTP、HTTP 或邮件密钥。
        return new MockMailService(jdbc,(id,subject,body)->MockMailService.Outcome.SUCCESS);
    }
    @Bean(initMethod="start",destroyMethod="stop")
    KafkaMessageListenerContainer<String,String> mockMailListener(MockMailService mail,ObjectMapper mapper,
            @Value("${notification.kafka.bootstrap-servers:localhost:9092}") String servers,
            @Value("${notification.kafka.topic:video.analysis.finished.v1}") String topic,
            @Value("${notification.kafka.group:video-mail-mock-v1}") String group) {
        var factory=new DefaultKafkaConsumerFactory<String,String>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,servers,ConsumerConfig.GROUP_ID_CONFIG,group,
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,StringDeserializer.class,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,false,ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,"earliest",
            ConsumerConfig.MAX_POLL_RECORDS_CONFIG,10));
        var props=new ContainerProperties(topic);
        props.setAckMode(ContainerProperties.AckMode.RECORD);
        props.setMessageListener((MessageListener<String,String>)record->{
            AnalysisFinishedEvent event;
            try {event=mapper.readValue(record.value(),AnalysisFinishedEvent.class);event.validate();}
            catch(Exception malformed) {
                org.slf4j.LoggerFactory.getLogger(MockMailConfig.class).warn("忽略无效通知事件 partition={} offset={}",record.partition(),record.offset());
                return;
            }
            mail.accept(event); // JDBC 成功返回后由容器提交位点；数据库异常不能吞掉。
        });
        var container=new KafkaMessageListenerContainer<>(factory,props);
        container.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(1000,FixedBackOff.UNLIMITED_ATTEMPTS)));
        return container;
    }
    @Bean(destroyMethod="shutdownNow") ScheduledExecutorService mockMailSender(MockMailService mail) {
        var timer=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"mock-mail-sender");t.setDaemon(true);return t;});
        timer.scheduleWithFixedDelay(()->{
            try {mail.tick();} catch(RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(MockMailConfig.class).warn("模拟邮件调度失败 type={}",e.getClass().getSimpleName());
            }
        },5,5,TimeUnit.SECONDS);
        return timer;
    }
}
