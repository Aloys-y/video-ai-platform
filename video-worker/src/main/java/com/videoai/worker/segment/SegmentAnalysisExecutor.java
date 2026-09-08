package com.videoai.worker.segment;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/** 父线程留在调用方；实例共享有界池只执行片段。Future.cancel 不作为实际收敛证据。 */
@Component
@lombok.extern.slf4j.Slf4j
public class SegmentAnalysisExecutor implements AutoCloseable {
    private final SegmentAnalysisProperties config;
    private final ThreadPoolExecutor pool;
    private final AtomicLong saturatedBatches = new AtomicLong();
    private long nextRequestNanos;

    @org.springframework.beans.factory.annotation.Autowired
    public SegmentAnalysisExecutor(SegmentAnalysisProperties config) {
        this(config, config.getThreads(), 0);
    }

    /** 包内对照实验入口；生产默认仍保持固定线程数。 */
    SegmentAnalysisExecutor(SegmentAnalysisProperties config, int maximumThreads, long keepAliveMs) {
        config.validate(); this.config = config;
        if (maximumThreads < config.getThreads() || maximumThreads > 64 || keepAliveMs < 0)
            throw new IllegalArgumentException("最大线程数或空闲回收时间无效");
        AtomicInteger id = new AtomicInteger();
        pool = new ThreadPoolExecutor(config.getThreads(), maximumThreads, keepAliveMs, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(config.getQueueCapacity()), runnable -> {
                    Thread thread = new Thread(runnable, "segment-analysis-" + id.incrementAndGet());
                    thread.setDaemon(true); return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    @FunctionalInterface public interface Work<T> { T run(Scope scope) throws Exception; }
    @FunctionalInterface public interface CheckedWrite<T> { T run() throws Exception; }

    /** 仅保护短写入，不在锁或事务中等待模型。关闭后禁止本批次迟到写入。 */
    public static final class Scope {
        private final Instant deadline;
        private final com.videoai.common.analysis.ExecutionOwnership.Token ownership = com.videoai.common.analysis.ExecutionOwnership.current();
        private volatile boolean closed;
        Scope(Instant deadline) { this.deadline = deadline; }
        public Instant deadline() { return deadline; }
        public void check() throws InterruptedException {
            if ((ownership != null && !ownership.valid()) || closed || Thread.currentThread().isInterrupted() || !Instant.now().isBefore(deadline))
                throw new InterruptedException("片段执行已取消或超时");
        }
        public synchronized <T> T write(CheckedWrite<T> action) throws Exception { check(); return action.run(); }
        synchronized void close() { closed = true; }
    }

    public static final class BatchFailure extends Exception {
        private final boolean converged;
        private final List<?> completed;
        private final List<Outcome> outcomes;
        private final boolean persistenceUnsettled;
        BatchFailure(String message, boolean converged, List<?> completed, List<Outcome> outcomes, boolean persistenceUnsettled) {
            super(message); this.converged = converged; this.completed = List.copyOf(completed);
            this.outcomes = List.copyOf(outcomes);
            this.persistenceUnsettled = persistenceUnsettled;
        }
        public boolean converged() { return converged; }
        public List<?> completed() { return completed; }
        public List<Outcome> outcomes() { return outcomes; }
        public boolean persistenceUnsettled() { return persistenceUnsettled; }
    }

    public enum OutcomeStatus { NOT_SUBMITTED, QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }
    /** index 对应本次 work 列表；取消后仍运行的工作不会伪装为已完成。 */
    public record Outcome(int index, OutcomeStatus status) {}

    private static final class Job<T> implements Runnable {
        private final Work<T> work;
        private final Scope scope;
        private final CountDownLatch finished;
        // 0=尚未启动，1=正在执行，2=真正退出或在启动前移除。
        private volatile int state;
        private Thread runner;
        private volatile OutcomeStatus status = OutcomeStatus.NOT_SUBMITTED;
        private boolean reported;
        private T value;
        private Throwable error;
        Job(Work<T> work, Scope scope, CountDownLatch finished) {
            this.work = work; this.scope = scope; this.finished = finished;
        }
        @Override public void run() {
            synchronized (this) {
                if (state != 0) return;
                state = 1; runner = Thread.currentThread(); status = OutcomeStatus.RUNNING;
            }
            try (var ownership = com.videoai.common.analysis.ExecutionOwnership.bind(scope.ownership);
                 var budget = com.videoai.common.analysis.ExecutionBudget.bind(scope.deadline())) {
                scope.check();
                value = work.run(scope);
            }
            catch (Throwable e) { error = e; }
            finally {
                synchronized (this) {
                    runner = null;
                    status = error == null ? OutcomeStatus.SUCCEEDED : OutcomeStatus.FAILED;
                    state = 2;
                    finished.countDown();
                }
            }
        }
        synchronized void accepted() {
            if (state == 0) status = OutcomeStatus.QUEUED;
        }
        synchronized void cancel(ThreadPoolExecutor pool) {
            if (state == 0) {
                pool.remove(this);
                if (status == OutcomeStatus.QUEUED) status = OutcomeStatus.CANCELLED;
                error = new CancellationException("片段未执行");
                state = 2; finished.countDown();
            } else if (state == 1) {
                // 与 run 的 finally 使用同一把锁，避免中断已复用该工作线程的其他任务。
                runner.interrupt();
            }
        }
    }

    /** 池满时持续等待，仅受整局期限/取消/关闭约束；Latch 统计实际退出或启动前取消。 */
    public <T> List<T> execute(List<Work<T>> work, Instant deadline, BooleanSupplier cancelled,
                               Consumer<T> onCompleted) throws BatchFailure {
        return execute(work, deadline, cancelled, onCompleted, (index, acceptedAt) -> {});
    }

    /** 包内压测观测点：成功提交那次 execute 调用的起点，用于拆分入队与队列等待。 */
    <T> List<T> execute(List<Work<T>> work, Instant deadline, BooleanSupplier cancelled,
                       Consumer<T> onCompleted, BiConsumer<Integer, Long> onAccepted) throws BatchFailure {
        if (Thread.currentThread().getName().startsWith("segment-analysis-"))
            throw new IllegalStateException("禁止在片段池内等待子任务");
        Scope scope = new Scope(deadline);
        CountDownLatch finished = new CountDownLatch(work.size());
        List<Job<T>> jobs = work.stream().map(w -> new Job<>(w, scope, finished)).toList();
        List<T> completed = new ArrayList<>();
        int next = 0; long nextParentCheck = 0; String failure = null;
        boolean interrupted = false, saturationRecorded = false, persistenceUnsettled = false;
        try {
            while (next < jobs.size() || finished.getCount() != 0) {
                scope.check();
                if (pool.isShutdown()) throw new InterruptedException("片段池已关闭");
                if (System.nanoTime() >= nextParentCheck) {
                    if (cancelled.getAsBoolean()) throw new InterruptedException("父任务已取消或执行代次失效");
                    nextParentCheck = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250);
                }
                collect(jobs, completed, onCompleted);
                if (next < jobs.size()) {
                    Job<T> job = jobs.get(next);
                    try {
                        long acceptedAt = System.nanoTime();
                        pool.execute(job); job.accepted(); onAccepted.accept(next, acceptedAt); next++;
                        continue;
                    } catch (RejectedExecutionException e) {
                        if (pool.isShutdown()) throw new InterruptedException("片段池已关闭");
                        if (!saturationRecorded) {
                            saturationRecorded = true;
                            saturatedBatches.incrementAndGet();
                            log.debug("片段队列繁忙，等待容量: active={}, queued={}, saturatedBatches={}",
                                    pool.getActiveCount(), pool.getQueue().size(), saturatedBatches.get());
                        }
                        // 当前片段不跳过；下面短周期等待后重试，不另设入队失败期限。
                    }
                }
                // 短周期 await 保留父线程进度回调、取消检查；不再使用完成队列补交。
                try { if (finished.await(20, TimeUnit.MILLISECONDS)) break; }
                catch (InterruptedException e) { interrupted = true; throw e; }
            }
            scope.check();
            if (cancelled.getAsBoolean()) throw new InterruptedException("父任务已取消或执行代次失效");
            collect(jobs, completed, onCompleted);
            if (failure == null && jobs.stream().anyMatch(j -> j.error != null)) failure = "部分片段分析失败，已保留成功结果";
        } catch (InterruptedException e) {
            interrupted |= Thread.currentThread().isInterrupted();
            failure = "片段阶段取消、中断或超过总期限";
        } catch (RuntimeException e) { failure = "片段协调或进度持久化失败"; persistenceUnsettled = isPersistenceFailure(e); }
        finally {
            scope.close();
            if (finished.getCount() != 0) {
                for (Job<T> job : jobs) job.cancel(pool);
                long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.getCancellationGraceMs());
                while (finished.getCount() != 0 && System.nanoTime() < until) {
                    try { finished.await(Math.max(1, until - System.nanoTime()), TimeUnit.NANOSECONDS); }
                    catch (InterruptedException e) { interrupted = true; }
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
        boolean converged = finished.getCount() == 0;
        if (failure != null) {
            // 取消收尾中的成功结果也保留；此时不再调用可能失败的进度回调。
            collect(jobs, completed, value -> {});
            List<Outcome> outcomes = new ArrayList<>();
            for (int i = 0; i < jobs.size(); i++) outcomes.add(new Outcome(i, jobs.get(i).status));
            throw new BatchFailure(failure, converged, completed, outcomes,
                    persistenceUnsettled || jobs.stream().anyMatch(j -> isPersistenceFailure(j.error)));
        }
        return List.copyOf(completed);
    }

    private static boolean isPersistenceFailure(Throwable error) {
        for (Throwable e = error; e != null; e = e.getCause())
            if (e instanceof org.springframework.dao.DataAccessException
                    || e instanceof org.springframework.transaction.TransactionException
                    || e instanceof com.videoai.worker.processor.UnsettledTaskException) return true;
        return false;
    }

    private static <T> void collect(List<Job<T>> jobs, List<T> completed, Consumer<T> callback) {
        for (Job<T> job : jobs) {
            if (job.state == 2 && !job.reported) {
                job.reported = true;
                if (job.error == null) { completed.add(job.value); callback.accept(job.value); }
            }
        }
    }

    /** 实例级请求启动间隔；排队、限速等待均消耗父任务剩余期限。 */
    public void awaitRequestPermit(Scope scope) throws InterruptedException {
        while (true) {
            scope.check();
            long delay;
            synchronized (this) {
                long now = System.nanoTime(); delay = nextRequestNanos - now;
                if (delay <= 0) {
                    nextRequestNanos = now + TimeUnit.MILLISECONDS.toNanos(config.getRequestIntervalMs()); return;
                }
            }
            TimeUnit.NANOSECONDS.sleep(Math.min(delay, TimeUnit.MILLISECONDS.toNanos(20)));
        }
    }

    /** saturatedBatches 统计遇到过满队列的批次，不代表批次失败。 */
    public record Stats(int active, int queued, long completed, long saturatedBatches) {}
    int poolSizeForTest() { return pool.getPoolSize(); }
    int largestPoolSizeForTest() { return pool.getLargestPoolSize(); }
    public Stats stats() { return new Stats(pool.getActiveCount(), pool.getQueue().size(), pool.getCompletedTaskCount(), saturatedBatches.get()); }
    @Override @PreDestroy public void close() { pool.shutdownNow(); }
}
