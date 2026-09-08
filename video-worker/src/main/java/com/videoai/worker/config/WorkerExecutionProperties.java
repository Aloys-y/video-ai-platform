package com.videoai.worker.config;

import lombok.Data;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "videoai.worker")
public class WorkerExecutionProperties {
    private int taskTimeoutMinutes = 30;
    private boolean asyncEnabled = false;
    public void validateKafka(KafkaProperties kafka) {
        long poll = Long.parseLong(kafka.getConsumer().getProperties().getOrDefault("max.poll.interval.ms", "300000"));
        if (taskTimeoutMinutes < 1 || taskTimeoutMinutes > 120 || kafka.getConsumer().getMaxPollRecords() == null
                || kafka.getConsumer().getMaxPollRecords() != 1 || (asyncEnabled ? poll < 10000 : poll <= taskTimeoutMinutes * 60000L + 60000)
                || !Boolean.FALSE.equals(kafka.getConsumer().getEnableAutoCommit()))
            throw new IllegalArgumentException(asyncEnabled
                    ? "异步消费要求关闭自动提交、每poll一条、poll间隔至少10秒及有效耗时提示阈值"
                    : "同步消费要求关闭自动提交、每poll一条且poll间隔超过任务期限并预留收尾时间");
    }
}
