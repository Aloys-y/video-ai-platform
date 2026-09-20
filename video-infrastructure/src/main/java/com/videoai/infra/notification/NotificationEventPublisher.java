package com.videoai.infra.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoai.common.domain.AnalysisFinishedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;

/** 提交后尽力发布；进程崩溃或队列满允许丢提示，不回滚视频结果。 */
public final class NotificationEventPublisher {
    private static final Logger log=LoggerFactory.getLogger(NotificationEventPublisher.class);
    private final KafkaTemplate<String,String> kafka;
    private final ObjectMapper mapper;
    private final String topic;
    private final ExecutorService executor=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64),r->{var t=new Thread(r,"notification-publisher");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    public NotificationEventPublisher(KafkaTemplate<String,String> kafka,ObjectMapper mapper,String topic) {
        this.kafka=kafka;this.mapper=mapper;this.topic=topic;
    }
    public void publish(AnalysisFinishedEvent event) {
        try {
            executor.execute(()->{
                try {
                    event.validate();
                    kafka.send(topic,event.taskId(),mapper.writeValueAsString(event)).whenComplete((result,error)->{
                        if(error!=null) log.warn("通知投递失败 eventId={} type={}",event.eventId(),error.getClass().getSimpleName());
                    });
                } catch(Exception e) {log.warn("通知发布失败 eventId={} type={}",event.eventId(),e.getClass().getSimpleName());}
            });
        } catch(RejectedExecutionException e) {log.warn("通知发布队列已满或关闭 eventId={}",event.eventId());}
    }
    @PreDestroy public void close() {executor.shutdownNow();}
}
