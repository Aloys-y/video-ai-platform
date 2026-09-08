package com.videoai.common.analysis;

import java.io.InterruptedIOException;
import java.time.*;

/** 同步调用链的总期限；子线程必须显式绑定，关闭后恢复，不能泄漏到线程池下一个任务。 */
public final class ExecutionBudget implements AutoCloseable {
    private static final class State {
        final Instant deadline; final java.util.function.BooleanSupplier cancelled; long nextCheck;
        State(Instant deadline, java.util.function.BooleanSupplier cancelled) { this.deadline = deadline; this.cancelled = cancelled; }
    }
    private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();
    private final State previous;
    private ExecutionBudget(Instant deadline, java.util.function.BooleanSupplier cancelled) {
        previous = CURRENT.get(); CURRENT.set(new State(deadline, cancelled));
    }
    public static ExecutionBudget bind(Instant deadline) { return new ExecutionBudget(deadline, () -> false); }
    public static ExecutionBudget bind(Instant deadline, java.util.function.BooleanSupplier cancelled) { return new ExecutionBudget(deadline, cancelled); }
    public static void check() throws InterruptedIOException {
        ExecutionOwnership.check();
        State state = CURRENT.get();
        if (Thread.currentThread().isInterrupted() || (state != null && !Instant.now().isBefore(state.deadline)))
            throw new InterruptedIOException("任务总期限已耗尽或执行已中断");
        if (state != null && System.nanoTime() >= state.nextCheck) {
            if (state.cancelled.getAsBoolean()) throw new InterruptedIOException("父任务已取消或执行代次失效");
            state.nextCheck = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(250);
        }
    }
    public static Duration limit(Duration maximum) throws InterruptedIOException {
        check(); Instant deadline = CURRENT.get() == null ? null : CURRENT.get().deadline;
        if (deadline == null) return maximum;
        Duration left = Duration.between(Instant.now(), deadline);
        if (left.isZero() || left.isNegative()) throw new InterruptedIOException("任务总期限已耗尽");
        return left.compareTo(maximum) < 0 ? left : maximum;
    }
    @Override public void close() { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
}
