package com.videoai.worker.scheduler;

import org.junit.jupiter.api.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Timeout(10)
class DatabaseTaskSchedulerTest {
    TaskDispatchRepository repository;
    final Queue<TaskDispatchRepository.Candidate> pending = new ConcurrentLinkedQueue<>();
    final List<DatabaseTaskScheduler> schedulers = new ArrayList<>();

    @BeforeEach void setup() {
        repository = mock(TaskDispatchRepository.class);
        when(repository.leaseSeconds()).thenReturn(90);
        when(repository.candidates(anyInt())).thenAnswer(i -> new ArrayList<>(pending));
        when(repository.claim(any())).thenAnswer(i -> {
            TaskDispatchRepository.Candidate c = i.getArgument(0);
            return pending.remove(c) ? new TaskDispatchRepository.Lease(c.taskId(), c.attemptNo(), UUID.randomUUID().toString()) : null;
        });
        when(repository.renew(any())).thenReturn(true);
        when(repository.releaseUnstarted(any())).thenReturn(true);
        when(repository.finish(any(), anyString(), nullable(String.class))).thenReturn(true);
    }
    @AfterEach void close() { schedulers.forEach(DatabaseTaskScheduler::close); }
    void tasks(int count) { for (int i=0;i<count;i++) pending.add(new TaskDispatchRepository.Candidate("task-"+i,0)); }
    DatabaseTaskScheduler scheduler(DatabaseTaskScheduler.Work work, int capacity) {
        var s = new DatabaseTaskScheduler(repository,work,capacity); schedulers.add(s); return s;
    }
    void await(BooleanSupplier condition) throws Exception {
        long end=System.nanoTime()+Duration.ofSeconds(3).toNanos();
        while(!condition.getAsBoolean()&&System.nanoTime()<end) Thread.sleep(5);
        assertTrue(condition.getAsBoolean(),"条件未在期限内满足");
    }

    @Test void capacityLimitsClaimingAndFinishesBeforeReleasing() throws Exception {
        tasks(8); var gate=new CountDownLatch(1); var started=new CountDownLatch(3);
        var s=scheduler(c->{started.countDown();gate.await();return DatabaseTaskScheduler.Outcome.succeeded();},3);
        try {
            s.scanOnce(); assertTrue(started.await(2,TimeUnit.SECONDS));
            s.scanOnce(); assertEquals(5,pending.size());assertEquals(0,s.stats().available());
            verify(repository,times(3)).claim(any());
            gate.countDown();await(()->s.stats().available()==3);
            verify(repository,times(3)).finish(any(),eq("SUCCEEDED"),isNull());
        } finally {gate.countDown();}
    }

    @Test void submissionRejectionReturnsClaimAndPermit() {
        tasks(1);
        var pool=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(1));pool.shutdown();
        var s=new DatabaseTaskScheduler(repository,c->{fail("不能执行");return null;},1,pool);schedulers.add(s);
        s.scanOnce();
        assertEquals(1,s.stats().available());assertEquals(0,s.stats().inFlight());assertEquals(1,s.stats().rejected());
        verify(repository).releaseUnstarted(any());
    }

    @Test void claimOutcomeUnknownNeverStartsWorkOrLeaksCapacity() {
        tasks(1);when(repository.claim(any())).thenThrow(new IllegalStateException("commit unknown"));
        var s=scheduler(c->{fail("不能执行未知领取");return null;},1);
        s.scanOnce();assertEquals(1,s.stats().available());assertEquals(0,s.stats().inFlight());
        verify(repository,never()).releaseUnstarted(any());
    }

    @Test void maintenanceRenewsIndependentlyAndFailureInterruptsWork() throws Exception {
        tasks(1);var started=new CountDownLatch(1);var interrupted=new AtomicBoolean();
        var s=scheduler(c->{started.countDown();try {Thread.sleep(60000);}catch(InterruptedException e){interrupted.set(true);throw e;}return null;},1);
        s.scanOnce();assertTrue(started.await(2,TimeUnit.SECONDS));
        s.renewOnce();verify(repository).renew(any());assertEquals(0,s.stats().available());
        when(repository.renew(any())).thenReturn(false);
        s.renewOnce();await(()->s.stats().available()==1);
        assertTrue(interrupted.get());verify(repository,never()).finish(any(),anyString(),nullable(String.class));
    }

    @Test void closeDoesNotReleaseSlotUntilUncooperativeWorkActuallyExits() throws Exception {
        tasks(1);var started=new CountDownLatch(1);var gate=new CountDownLatch(1);
        var s=scheduler(c->{started.countDown();while(gate.getCount()>0){try{gate.await();}catch(InterruptedException ignored){}}
            return DatabaseTaskScheduler.Outcome.succeeded();},1);
        try {
            s.scanOnce();assertTrue(started.await(2,TimeUnit.SECONDS));s.close();
            assertEquals(0,s.stats().available());assertEquals(1,s.stats().inFlight());
            gate.countDown();await(()->s.stats().available()==1);
            verify(repository,never()).finish(any(),anyString(),nullable(String.class));
        } finally {gate.countDown();}
    }

    @Test void childMustActuallyExitBeforeCapacityIsReused() throws Exception {
        tasks(1);var child=new AtomicReference<AutoCloseable>();var returned=new CountDownLatch(1);
        var s=scheduler(c->{child.set(c.retainChild());returned.countDown();return DatabaseTaskScheduler.Outcome.succeeded();},1);
        try {
            s.scanOnce();assertTrue(returned.await(2,TimeUnit.SECONDS));await(()->s.stats().active()==0);
            assertEquals(0,s.stats().available());assertEquals(1,s.stats().inFlight());
            verify(repository,never()).finish(any(),anyString(),nullable(String.class));
            child.get().close();child.get().close();assertEquals(1,s.stats().available());
        } finally {if(child.get()!=null)child.get().close();}
    }

    @Test void recurringScanSurvivesDatabaseException() throws Exception {
        tasks(1);var once=new AtomicBoolean();
        when(repository.failExpired(anyInt())).thenAnswer(i->{if(!once.getAndSet(true))throw new IllegalStateException("offline");return 0;});
        var s=scheduler(c->DatabaseTaskScheduler.Outcome.succeeded(),1);
        s.start(20,100);
        await(()->pending.isEmpty()&&s.stats().available()==1);
        verify(repository).finish(any(),eq("SUCCEEDED"),isNull());
    }

    @Test void businessFailureDoesNotRequeueAndLeavesExpiryAsRecoveryPath() throws Exception {
        tasks(1);var s=scheduler(c->{throw new IllegalStateException("unexpected");},1);
        s.scanOnce();await(()->s.stats().available()==1);
        verify(repository,never()).releaseUnstarted(any());
        verify(repository,never()).finish(any(),anyString(),nullable(String.class));
        assertTrue(pending.isEmpty());
    }
}
