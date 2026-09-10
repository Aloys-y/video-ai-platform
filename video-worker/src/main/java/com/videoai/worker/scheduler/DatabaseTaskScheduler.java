package com.videoai.worker.scheduler;

import lombok.extern.slf4j.Slf4j;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** 数据库派发器；定时维护线程不执行视频业务。 */
@Slf4j
public final class DatabaseTaskScheduler implements AutoCloseable {
    @FunctionalInterface public interface Work { Outcome execute(Context context) throws Exception; }
    public record Outcome(String status, String errorCode) {
        public Outcome {
            if (!Set.of("SUCCEEDED", "PARTIAL", "FAILED").contains(status))
                throw new IllegalArgumentException("无效任务终态");
        }
        public static Outcome succeeded() { return new Outcome("SUCCEEDED", null); }
    }

    /** 片段线程显式携带该上下文；每个已提交子任务必须登记并在实际退出时关闭登记。 */
    public final class Context {
        private final Slot slot;
        private Context(Slot slot) { this.slot = slot; }
        public TaskDispatchRepository.Lease lease() { return slot.lease; }
        public com.videoai.common.analysis.ExecutionOwnership.Token token() {
            return new com.videoai.common.analysis.ExecutionOwnership.Token(slot.lease.taskId(),slot.lease.attemptNo(),slot.lease.owner(),slot::valid,()->{
                try {return retainChild();} catch(InterruptedException e){throw new IllegalStateException("执行权失效",e);}
            });
        }
        public void check() throws InterruptedException {
            if (!slot.valid() || Thread.currentThread().isInterrupted())
                throw new InterruptedException("数据库任务执行权失效或任务已取消");
        }
        public AutoCloseable retainChild() throws InterruptedException {
            synchronized (slot) { check(); slot.children++; }
            var released = new AtomicBoolean();
            return () -> {
                if (released.compareAndSet(false, true)) synchronized (slot) {
                    slot.children--; slot.releaseIfSettled();
                }
            };
        }
    }

    private final TaskDispatchRepository repository;
    private final Work work;
    private final Semaphore capacity;
    private final ThreadPoolExecutor videos;
    private final ScheduledExecutorService polling;
    private final ScheduledExecutorService renewal;
    private final ConcurrentMap<String, Slot> slots = new ConcurrentHashMap<>();
    private final AtomicBoolean scanning = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final LongAdder rejected = new LongAdder();

    public DatabaseTaskScheduler(TaskDispatchRepository repository, Work work, int concurrency) {
        this(repository, work, concurrency, new ThreadPoolExecutor(concurrency, concurrency, 0,
                TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(concurrency), factory("database-video"),
                new ThreadPoolExecutor.AbortPolicy()));
    }

    /** 包内注入执行器用于验证提交拒绝。 */
    DatabaseTaskScheduler(TaskDispatchRepository repository, Work work, int concurrency, ThreadPoolExecutor videos) {
        if (concurrency < 1 || concurrency > 32) throw new IllegalArgumentException("视频并发无效");
        this.repository = Objects.requireNonNull(repository);
        this.work = Objects.requireNonNull(work);
        this.capacity = new Semaphore(concurrency);
        this.videos = Objects.requireNonNull(videos);
        polling = Executors.newSingleThreadScheduledExecutor(factory("database-dispatch"));
        renewal = Executors.newSingleThreadScheduledExecutor(factory("database-renewal"));
    }

    private static ThreadFactory factory(String prefix) {
        var ids = new AtomicInteger();
        return job -> { var thread = new Thread(job, prefix + "-" + ids.incrementAndGet()); thread.setDaemon(true); return thread; };
    }

    public synchronized void start() { start(1000, 20000); }
    synchronized void start(long scanDelayMs, long renewalDelayMs) {
        if (scanDelayMs < 1 || renewalDelayMs < 1
                || renewalDelayMs >= repository.leaseSeconds() * 1000L)
            throw new IllegalArgumentException("调度间隔无效或续租间隔超过租约");
        if (closed.get()) throw new IllegalStateException("调度器已关闭");
        if (!started.compareAndSet(false, true)) return;
        polling.scheduleWithFixedDelay(this::scanOnce, 0, scanDelayMs, TimeUnit.MILLISECONDS);
        renewal.scheduleWithFixedDelay(this::renewOnce, renewalDelayMs, renewalDelayMs, TimeUnit.MILLISECONDS);
    }

    void scanOnce() {
        if (closed.get() || !scanning.compareAndSet(false, true)) return;
        try {
            repository.failExpired(12);
            // 每轮有限领取；没有容量时不查询待执行任务。
            int budget = capacity.availablePermits();
            for (int i = 0; i < budget && !closed.get() && capacity.tryAcquire(); i++) {
                Slot accepted = null;
                try {
                    for (var candidate : repository.candidates(12)) {
                        if (closed.get()) break;
                        long began = System.nanoTime();
                        var lease = repository.claim(candidate);
                        if (lease == null) continue;
                        accepted = new Slot(lease, began + TimeUnit.SECONDS.toNanos(repository.leaseSeconds()));
                        slots.put(lease.owner(), accepted);
                        if (closed.get()) { accepted.returnUnstarted(); break; }
                        try { videos.execute(accepted); }
                        catch (RejectedExecutionException e) { rejected.increment(); accepted.returnUnstarted(); }
                        break;
                    }
                } finally {
                    if (accepted == null) capacity.release();
                }
                if (accepted == null) break;
            }
        } catch (Exception e) {
            // 不输出可能包含连接信息的异常消息；周期任务不能因数据库异常终止。
            log.warn("数据库任务扫描失败: type={}", e.getClass().getSimpleName());
        } finally { scanning.set(false); }
    }

    void renewOnce() {
        if (closed.get()) return;
        for (var slot : slots.values()) synchronized (slot) {
            if (slot.ended || slot.released) continue;
            if (!slot.valid()) { slot.invalidate(); continue; }
            long began = System.nanoTime();
            try {
                if (!repository.renew(slot.lease)) slot.invalidate();
                else if (slot.valid()) slot.until = began + TimeUnit.SECONDS.toNanos(repository.leaseSeconds());
                else slot.invalidate();
            } catch (Exception e) {
                slot.invalidate();
                log.warn("数据库任务续租失败: taskId={}, type={}", slot.lease.taskId(), e.getClass().getSimpleName());
            }
        }
    }

    private final class Slot implements Runnable {
        final TaskDispatchRepository.Lease lease;
        volatile long until;
        volatile boolean invalid;
        Thread runner;
        boolean begun, ended, released;
        int children;
        Slot(TaskDispatchRepository.Lease lease, long until) { this.lease = lease; this.until = until; }
        boolean valid() { return !invalid && !closed.get() && System.nanoTime() < until; }
        synchronized void invalidate() {
            invalid = true;
            // 与run的runner清理互斥，避免中断已复用的工作线程。
            if (runner != null) runner.interrupt();
        }
        synchronized void returnUnstarted() {
            if (begun || ended) return;
            invalid = true;
            try { repository.releaseUnstarted(lease); }
            catch (Exception e) { log.warn("派发回退失败，等待租约到期: taskId={}", lease.taskId()); }
            finally { ended = true; releaseIfSettled(); }
        }
        synchronized void releaseIfSettled() {
            if (ended && children == 0 && !released) {
                released = true; slots.remove(lease.owner(), this); capacity.release();
            }
        }
        @Override public void run() {
            synchronized (this) {
                if (ended) return;
                begun = true; runner = Thread.currentThread();
            }
            var context = new Context(this);
            try {
                context.check();
                var outcome = Objects.requireNonNull(work.execute(context));
                context.check();
                synchronized (this) {
                    if (children != 0) throw new IllegalStateException("业务返回时片段尚未退出");
                    repository.finish(lease, outcome.status(), outcome.errorCode());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                // 未分类异常不能擅自重跑或抹掉部分结果。停止续租，由过期清理明确失败。
                log.warn("视频执行未正常收尾: taskId={}, type={}", lease.taskId(), e.getClass().getSimpleName());
            } finally {
                synchronized (this) {
                    invalid = true; runner = null; ended = true; releaseIfSettled();
                }
            }
        }
    }

    public record Stats(int available, int inFlight, int active, int queued, long rejected) {}
    public Stats stats() { return new Stats(capacity.availablePermits(), slots.size(), videos.getActiveCount(), videos.getQueue().size(), rejected.sum()); }

    @Override public synchronized void close() {
        if (!closed.compareAndSet(false, true)) return;
        polling.shutdownNow(); renewal.shutdownNow();
        slots.values().forEach(Slot::invalidate);
        for (var pending : videos.shutdownNow()) ((Slot) pending).returnUnstarted();
        // 正在执行或有未退出片段的槽位由其真实finally归还，不提前伪造完成。
    }
}
