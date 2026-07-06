package com.videoai.worker.scheduler;

import com.videoai.infra.mysql.mapper.TaskOutboxMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 恢复卡在 SENDING 的 Outbox
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxRecoveryScheduler {

    private final TaskOutboxMapper taskOutboxMapper;

    @Scheduled(fixedDelayString = "${videoai.outbox.recovery-interval-ms:60000}")
    public void recover() {
        int rows = taskOutboxMapper.recoverStuckSending(LocalDateTime.now().minusMinutes(1));
        if (rows > 0) {
            log.warn("Recovered stuck outbox rows: {}", rows);
        }
    }
}
