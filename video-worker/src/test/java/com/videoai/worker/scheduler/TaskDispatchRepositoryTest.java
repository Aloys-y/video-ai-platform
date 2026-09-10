package com.videoai.worker.scheduler;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class TaskDispatchRepositoryTest {
    JdbcTemplate jdbc;
    TaskDispatchRepository repository;

    @BeforeEach void setup() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        repository = new TaskDispatchRepository(source, new DataSourceTransactionManager(source), 90);
        jdbc.execute("CREATE TABLE analysis_task(task_id VARCHAR(64) PRIMARY KEY,attempt_no INT NOT NULL DEFAULT 0,"
                + "status VARCHAR(16),owner_token VARCHAR(64),lease_until TIMESTAMP(3),created_at TIMESTAMP(3) "
                + "DEFAULT CURRENT_TIMESTAMP,started_at TIMESTAMP(3),finished_at TIMESTAMP(3),error_code VARCHAR(64))");
        jdbc.update("INSERT INTO analysis_task(task_id,status) VALUES ('task','PENDING')");
    }
    @AfterEach void close() { jdbc.execute("DROP ALL OBJECTS"); }
    TaskDispatchRepository.Candidate candidate() { return new TaskDispatchRepository.Candidate("task", 0); }

    @Test void competingConnectionsHaveOneWinner() throws Exception {
        var pool = Executors.newFixedThreadPool(8);
        var gate = new CountDownLatch(1);
        try {
            var futures = new ArrayList<Future<TaskDispatchRepository.Lease>>();
            for (int i=0;i<8;i++) futures.add(pool.submit(() -> {gate.await();return repository.claim(candidate());}));
            gate.countDown();
            int winners=0;
            for(var f:futures) if(f.get()!=null) winners++;
            assertEquals(1,winners);
            assertTrue(repository.candidates(3).isEmpty());
        } finally {pool.shutdownNow();assertTrue(pool.awaitTermination(2,TimeUnit.SECONDS));}
    }

    @Test void rejectedSubmissionCanReleaseButOldOwnerCannotTouchNewLease() {
        var first=repository.claim(candidate());
        assertTrue(repository.releaseUnstarted(first));
        var second=repository.claim(candidate());
        assertNotEquals(first.owner(),second.owner());
        assertFalse(repository.renew(first));
        assertFalse(repository.releaseUnstarted(first));
        assertFalse(repository.finish(first,"SUCCEEDED",null));
        assertTrue(repository.renew(second));
    }

    @Test void expiredLeaseCannotReviveOrWriteAndIsNotAutomaticallyRequeued() {
        var lease=repository.claim(candidate());
        jdbc.update("UPDATE analysis_task SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(3))");
        assertFalse(repository.renew(lease));
        var wrote=new AtomicBoolean();
        assertThrows(IllegalStateException.class,()->repository.fenced(lease,()->{wrote.set(true);return null;}));
        assertFalse(wrote.get());
        assertEquals(1,repository.failExpired(10));
        assertEquals(0,repository.failExpired(10));
        assertTrue(repository.candidates(10).isEmpty());
        assertEquals("FAILED",jdbc.queryForObject("SELECT status FROM analysis_task",String.class));
    }

    @Test void finishAndCancellationRejectLateWrites() {
        var lease=repository.claim(candidate());
        assertTrue(repository.finish(lease,"SUCCEEDED",null));
        assertFalse(repository.finish(lease,"FAILED","late"));
        assertNull(repository.claim(candidate()));
        jdbc.update("UPDATE analysis_task SET status='PENDING',attempt_no=1");
        assertNull(repository.claim(candidate()));
        var next=repository.claim(new TaskDispatchRepository.Candidate("task",1));
        jdbc.update("UPDATE analysis_task SET status='CANCELLED'");
        assertFalse(repository.renew(next));
        assertFalse(repository.finish(next,"SUCCEEDED",null));
    }

    @Test void fencedActionRollsBackTogether() {
        var lease=repository.claim(candidate());
        assertThrows(IllegalArgumentException.class,()->repository.fenced(lease,()->{
            jdbc.update("UPDATE analysis_task SET error_code='temporary' WHERE task_id='task'");
            throw new IllegalArgumentException("rollback");
        }));
        assertNull(jdbc.queryForObject("SELECT error_code FROM analysis_task",String.class));
    }

    @Test void schedulerCompletesDatabaseTaskAndDoesNotClaimItAgain() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        try (var scheduler = new DatabaseTaskScheduler(repository, context -> {
            calls.incrementAndGet(); context.check(); entered.countDown(); release.await();
            return DatabaseTaskScheduler.Outcome.succeeded();
        }, 1)) {
            try {
                scheduler.scanOnce();
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertEquals("RUNNING", jdbc.queryForObject("SELECT status FROM analysis_task", String.class));
                scheduler.renewOnce(); release.countDown();
                long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (scheduler.stats().available() != 1 && System.nanoTime() < end) Thread.sleep(5);
                assertEquals(1, scheduler.stats().available());
                assertEquals("SUCCEEDED", jdbc.queryForObject("SELECT status FROM analysis_task", String.class));
                scheduler.scanOnce(); assertEquals(1, calls.get());
            } finally { release.countDown(); }
        }
    }
}
