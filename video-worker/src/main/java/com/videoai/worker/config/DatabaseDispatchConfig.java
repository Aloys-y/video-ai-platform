package com.videoai.worker.config;

import com.videoai.worker.processor.VideoAnalysisService;
import com.videoai.worker.scheduler.*;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.PlatformTransactionManager;
import javax.sql.DataSource;

@Configuration
public class DatabaseDispatchConfig {
    @Bean
    TaskDispatchRepository taskDispatchRepository(DataSource source,PlatformTransactionManager transactions) {
        return new TaskDispatchRepository(source,transactions,90);
    }
    @Bean(initMethod="start",destroyMethod="close")
    DatabaseTaskScheduler databaseTaskScheduler(TaskDispatchRepository repository,VideoAnalysisService service,
            @Value("${videoai.dispatch.video-concurrency:3}") int concurrency) {
        return new DatabaseTaskScheduler(repository,service,concurrency);
    }
}
