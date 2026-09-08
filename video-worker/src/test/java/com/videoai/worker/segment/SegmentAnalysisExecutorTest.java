package com.videoai.worker.segment;

import org.junit.jupiter.api.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class SegmentAnalysisExecutorTest {
    private final ExecutorService parents = Executors.newCachedThreadPool();
    @AfterEach void close() { parents.shutdownNow(); }
    private SegmentAnalysisProperties config(int threads, int queue) {
        var p = new SegmentAnalysisProperties(); p.setThreads(threads); p.setQueueCapacity(queue);
        p.setRequestIntervalMs(1); p.setCancellationGraceMs(100); return p;
    }
    private Instant deadline() { return Instant.now().plusSeconds(5); }
    static void await(BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.getAsBoolean() && System.nanoTime() < until) Thread.sleep(2);
        assertTrue(condition.getAsBoolean());
    }

    @Test void elasticPoolExpandsWhenQueueIsFullAndRetiresOnlyExtraThreads() throws Exception {
        try (var executor = new SegmentAnalysisExecutor(config(2, 1), 4, 100)) {
            var release = new CountDownLatch(1);
            List<SegmentAnalysisExecutor.Work<Integer>> work = new ArrayList<>();
            for (int i=0;i<5;i++) { final int id=i; work.add(scope -> {release.await();return id;}); }
            var run=parents.submit(() -> executor.execute(work,deadline(),()->false,v->{}));
            try {
                await(() -> executor.stats().active()==4 && executor.stats().queued()==1);
                assertEquals(4,executor.largestPoolSizeForTest());
            } finally {release.countDown();}
            assertEquals(5,run.get(2,TimeUnit.SECONDS).size());
            await(() -> executor.poolSizeForTest()==2);
        }
    }

    @Test void multipleParentsQueueAllWorkAndRespectGlobalLimit() throws Exception {
        try (var executor = new SegmentAnalysisExecutor(config(3, 16))) {
            var permits = new Semaphore(0); var started = new CountDownLatch(3);
            var global = new AtomicInteger(); var peak = new AtomicInteger();
            List<Future<List<Integer>>> runs = new ArrayList<>();
            for (int parent = 0; parent < 2; parent++) {
                List<SegmentAnalysisExecutor.Work<Integer>> work = new ArrayList<>();
                for (int n = 0; n < 5; n++) {
                    int value = n;
                    work.add(scope -> {
                        peak.accumulateAndGet(global.incrementAndGet(), Math::max);
                        started.countDown();
                        try { permits.acquire(); return value; }
                        finally { global.decrementAndGet(); }
                    });
                }
                runs.add(parents.submit(() -> executor.execute(work, deadline(), () -> false, v -> {})));
            }
            try {
                assertTrue(started.await(2, TimeUnit.SECONDS)); assertEquals(3, peak.get());
                await(() -> executor.stats().queued() == 7);
                assertTrue(runs.stream().noneMatch(Future::isDone));
            } finally { permits.release(20); }
            for (var run : runs) assertEquals(5, run.get().size());
            assertEquals(3, peak.get());
        }
    }

    @Test void failureAllowsQueuedWorkAndWaitsForStartedSuccess() throws Exception {
        try (var executor = new SegmentAnalysisExecutor(config(2, 2))) {
            var both = new CountDownLatch(2); var release = new CountDownLatch(1); var third = new AtomicBoolean();
            var saved = new CopyOnWriteArrayList<Integer>();
            List<SegmentAnalysisExecutor.Work<Integer>> work = List.of(
                    scope -> { both.countDown(); both.await(); throw new IllegalStateException("failed"); },
                    scope -> { both.countDown(); release.await(); return scope.write(() -> 2); },
                    scope -> { third.set(true); return 3; });
            var parent = parents.submit(() -> executor.execute(work, deadline(), () -> false, saved::add));
            assertTrue(both.await(2, TimeUnit.SECONDS));
            await(() -> executor.stats().completed() >= 1);
            await(third::get); assertFalse(parent.isDone());
            release.countDown();
            var failure = assertThrows(ExecutionException.class, parent::get);
            var batch = assertInstanceOf(SegmentAnalysisExecutor.BatchFailure.class, failure.getCause());
            assertTrue(batch.converged()); assertEquals(Set.of(2, 3), new HashSet<>(saved)); assertTrue(third.get());
            assertEquals(List.of(SegmentAnalysisExecutor.OutcomeStatus.FAILED,
                    SegmentAnalysisExecutor.OutcomeStatus.SUCCEEDED, SegmentAnalysisExecutor.OutcomeStatus.SUCCEEDED),
                    batch.outcomes().stream().map(SegmentAnalysisExecutor.Outcome::status).toList());
        }
    }

    @Test void saturatedParentKeepsWaitingThenCompletesWithoutDroppingWork() throws Exception {
        var p = config(1, 1);
        try (var executor = new SegmentAnalysisExecutor(p)) {
            var release = new CountDownLatch(1); var started = new CountDownLatch(1);
            List<SegmentAnalysisExecutor.Work<Integer>> work = List.of(scope -> { started.countDown(); release.await(); return 1; });
            var a = parents.submit(() -> executor.execute(work, deadline(), () -> false, v -> {}));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            var b = parents.submit(() -> executor.execute(work, deadline(), () -> false, v -> {}));
            await(() -> executor.stats().queued() == 1);
            var c = parents.submit(() -> executor.execute(work, deadline(), () -> false, v -> {}));
            try {
                await(() -> executor.stats().saturatedBatches() == 1);
                assertThrows(TimeoutException.class, () -> c.get(150, TimeUnit.MILLISECONDS));
                assertEquals(1, executor.stats().saturatedBatches(), "多次重试只统计一次拥堵批次");
            } finally { release.countDown(); }
            assertEquals(List.of(1), a.get()); assertEquals(List.of(1), b.get()); assertEquals(List.of(1), c.get());
        }
    }

    @Test void cancellationDoesNotMistakeInterruptForConvergenceOrAllowLateWrites() throws Exception {
        try (var executor = new SegmentAnalysisExecutor(config(1, 1))) {
            var started = new CountDownLatch(1); var release = new CountDownLatch(1); var exited = new CountDownLatch(1);
            var cancel = new AtomicBoolean(); var writes = new AtomicInteger();
            List<SegmentAnalysisExecutor.Work<Integer>> work = List.of(scope -> {
                started.countDown();
                try {
                    boolean done = false;
                    while (!done) { try { release.await(); done = true; } catch (InterruptedException ignored) {} }
                    return scope.write(writes::incrementAndGet);
                } finally { exited.countDown(); }
            });
            var parent = parents.submit(() -> executor.execute(work, deadline(), cancel::get, v -> {}));
            assertTrue(started.await(2, TimeUnit.SECONDS)); cancel.set(true);
            try {
                var failure = assertThrows(ExecutionException.class, () -> parent.get(2, TimeUnit.SECONDS));
                assertFalse(((SegmentAnalysisExecutor.BatchFailure) failure.getCause()).converged());
                assertEquals(0, writes.get());
            } finally { release.countDown(); }
            assertTrue(exited.await(2, TimeUnit.SECONDS)); assertEquals(0, writes.get());
        }
    }

    @Test void totalDeadlineInterruptsWorkersAndProgressFailureCannotSucceed() throws Exception {
        try (var executor = new SegmentAnalysisExecutor(config(1, 1))) {
            var started = new CountDownLatch(1);
            List<SegmentAnalysisExecutor.Work<Integer>> blocked = List.of(scope -> { started.countDown(); new CountDownLatch(1).await(); return 1; });
            var run = parents.submit(() -> executor.execute(blocked, Instant.now().plusMillis(200), () -> false, v -> {}));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            var failed = assertThrows(ExecutionException.class, run::get);
            assertTrue(((SegmentAnalysisExecutor.BatchFailure)failed.getCause()).converged());
            assertThrows(SegmentAnalysisExecutor.BatchFailure.class, () -> executor.execute(
                    List.of(scope -> 1), deadline(), () -> false, v -> { throw new IllegalStateException("DB unavailable"); }));
        }
    }

    @Test void requestRateIsIndependentOfThreadCountAndNestedParentsAreRejected() throws Exception {
        var p = config(2, 2); p.setRequestIntervalMs(40);
        try (var executor = new SegmentAnalysisExecutor(p)) {
            List<Long> times = new CopyOnWriteArrayList<>();
            SegmentAnalysisExecutor.Work<Integer> work = scope -> { executor.awaitRequestPermit(scope); times.add(System.nanoTime()); return 1; };
            executor.execute(List.of(work, work, work), deadline(), () -> false, v -> {});
            times.sort(Long::compare);
            for (int n = 1; n < times.size(); n++) assertTrue(times.get(n) - times.get(n - 1) >= TimeUnit.MILLISECONDS.toNanos(35));
            executor.execute(List.of(scope -> {
                assertThrows(IllegalStateException.class, () -> executor.execute(List.of(), deadline(), () -> false, v -> {}));
                return 1;
            }), deadline(), () -> false, v -> {});
        }
    }

    @Test void failureDoesNotCancelQueuedSiblingFromSameBatch() throws Exception {
        try (var executor = new SegmentAnalysisExecutor(config(2, 2))) {
            var blockerStarted = new CountDownLatch(1); var releaseBlocker = new CountDownLatch(1);
            var failingStarted = new CountDownLatch(1); var releaseFailure = new CountDownLatch(1);
            var unexpected = new AtomicInteger();
            var other = parents.submit(() -> executor.execute(List.of(scope -> {
                blockerStarted.countDown(); releaseBlocker.await(); return 1;
            }), deadline(), () -> false, v -> {}));
            assertTrue(blockerStarted.await(2, TimeUnit.SECONDS));
            List<SegmentAnalysisExecutor.Work<Integer>> work = List.of(scope -> {
                failingStarted.countDown(); releaseFailure.await(); throw new IllegalStateException("failed");
            }, scope -> unexpected.incrementAndGet());
            var target = parents.submit(() -> executor.execute(work, deadline(), () -> false, v -> {}));
            try {
                assertTrue(failingStarted.await(2, TimeUnit.SECONDS)); await(() -> executor.stats().queued() == 1);
                releaseFailure.countDown(); assertThrows(ExecutionException.class, target::get);
                assertEquals(1, unexpected.get()); assertEquals(0, executor.stats().queued());
            } finally { releaseFailure.countDown(); releaseBlocker.countDown(); }
            other.get();
        }
    }

    @Test void interruptingParentPreservesInterruptAndWaitsForWorkerExit() throws Exception {
        try (var executor = new SegmentAnalysisExecutor(config(1, 1))) {
            var started = new CountDownLatch(1); var exited = new CountDownLatch(1);
            var thread = new AtomicReference<Thread>();
            var run = parents.submit(() -> {
                thread.set(Thread.currentThread());
                try {
                    executor.execute(List.of(scope -> {
                        started.countDown();
                        try { new CountDownLatch(1).await(); return 1; } finally { exited.countDown(); }
                    }), deadline(), () -> false, v -> {});
                    return false;
                } catch (SegmentAnalysisExecutor.BatchFailure e) {
                    return e.converged() && Thread.currentThread().isInterrupted();
                }
            });
            assertTrue(started.await(2, TimeUnit.SECONDS)); thread.get().interrupt();
            assertTrue(run.get()); assertEquals(0, exited.getCount());
        }
    }

    @Test void cancellationDuringAdmissionAccountsForQueuedAndUnsubmittedWork() throws Exception {
        var p = config(1, 1);
        try (var executor = new SegmentAnalysisExecutor(p)) {
            var started = new CountDownLatch(1); var release = new CountDownLatch(1);
            var cancel = new AtomicBoolean();
            List<SegmentAnalysisExecutor.Work<Integer>> work = List.of(
                    scope -> { started.countDown(); release.await(); return 0; },
                    scope -> 1, scope -> 2, scope -> 3);
            var run = parents.submit(() -> executor.execute(work, deadline(), cancel::get, v -> {}));
            try {
                assertTrue(started.await(2, TimeUnit.SECONDS));
                await(() -> executor.stats().saturatedBatches() == 1);
                assertFalse(run.isDone()); cancel.set(true);
                var failure = assertInstanceOf(SegmentAnalysisExecutor.BatchFailure.class,
                        assertThrows(ExecutionException.class, run::get).getCause());
                assertTrue(failure.converged()); assertTrue(failure.completed().isEmpty());
                assertEquals(List.of(SegmentAnalysisExecutor.OutcomeStatus.FAILED,
                        SegmentAnalysisExecutor.OutcomeStatus.CANCELLED,
                        SegmentAnalysisExecutor.OutcomeStatus.NOT_SUBMITTED,
                        SegmentAnalysisExecutor.OutcomeStatus.NOT_SUBMITTED),
                        failure.outcomes().stream().map(SegmentAnalysisExecutor.Outcome::status).toList());
            } finally { release.countDown(); }
        }
    }

    @Test void temporarySaturationWaitsAndNeverExecutesOnParentThread() throws Exception {
        var p = config(1, 1);
        try (var executor = new SegmentAnalysisExecutor(p)) {
            var release = new CountDownLatch(1); var callbackThread = new AtomicReference<Thread>();
            var submitting = new AtomicReference<Thread>(); var calls = new AtomicInteger();
            SegmentAnalysisExecutor.Work<Integer> item = scope -> {
                assertNotSame(submitting.get(), Thread.currentThread());
                release.await(); return calls.incrementAndGet();
            };
            var run = parents.submit(() -> {
                submitting.set(Thread.currentThread());
                return executor.execute(List.of(item, item, item, item), deadline(), () -> false,
                        value -> callbackThread.set(Thread.currentThread()));
            });
            try {
                await(() -> executor.stats().active() == 1 && executor.stats().queued() == 1);
                assertFalse(run.isDone()); release.countDown();
                assertEquals(4, run.get().size()); assertEquals(4, calls.get());
                assertEquals(submitting.get(), callbackThread.get());
            } finally { release.countDown(); }
        }
    }

    @Test void shutdownAccountsForQueuedAndUnsubmittedTasksWithoutHanging() throws Exception {
        try (var executor = new SegmentAnalysisExecutor(config(1, 1))) {
            var started = new CountDownLatch(1); var unexpected = new AtomicInteger();
            List<SegmentAnalysisExecutor.Work<Integer>> work = List.of(
                    scope -> { started.countDown(); new CountDownLatch(1).await(); return 0; },
                    scope -> unexpected.incrementAndGet(), scope -> unexpected.incrementAndGet());
            var run = parents.submit(() -> executor.execute(work, deadline(), () -> false, v -> {}));
            assertTrue(started.await(2, TimeUnit.SECONDS)); await(() -> executor.stats().queued() == 1);
            executor.close();
            var failure = assertInstanceOf(SegmentAnalysisExecutor.BatchFailure.class,
                    assertThrows(ExecutionException.class, () -> run.get(2, TimeUnit.SECONDS)).getCause());
            assertTrue(failure.converged()); assertEquals(0, unexpected.get());
            assertEquals(SegmentAnalysisExecutor.OutcomeStatus.CANCELLED, failure.outcomes().get(1).status());
            assertEquals(SegmentAnalysisExecutor.OutcomeStatus.NOT_SUBMITTED, failure.outcomes().get(2).status());
        }
    }

    @Test void taskDeadlineStopsAdmissionAndRemovesQueuedWork() throws Exception {
        var p = config(1, 1);
        try (var executor = new SegmentAnalysisExecutor(p)) {
            var unexpected = new AtomicInteger();
            List<SegmentAnalysisExecutor.Work<Integer>> work = List.of(
                    scope -> { new CountDownLatch(1).await(); return 0; },
                    scope -> unexpected.incrementAndGet(), scope -> unexpected.incrementAndGet());
            var failure = assertThrows(SegmentAnalysisExecutor.BatchFailure.class,
                    () -> executor.execute(work, Instant.now().plusMillis(150), () -> false, v -> {}));
            assertTrue(failure.converged()); assertEquals(0, unexpected.get());
            assertEquals(0, executor.stats().queued()); assertEquals(3, failure.outcomes().size());
            assertEquals(SegmentAnalysisExecutor.OutcomeStatus.NOT_SUBMITTED, failure.outcomes().get(2).status());
        }
    }
}
