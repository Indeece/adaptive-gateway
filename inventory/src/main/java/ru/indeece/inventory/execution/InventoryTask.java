package ru.indeece.inventory.execution;

import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Одна задача с сохранённым профилем и дедлайном, учитываемая до фактического выхода из работы.
 */
final class InventoryTask<T> extends FutureTask<T> {
    final long queuedAtNanos;
    final long deadlineNanos;
    final long durationMs;
    final long faultRevision;

    InventoryTask(Callable<T> work, long queuedAtNanos, long deadlineNanos,
                  long durationMs, long faultRevision, AtomicInteger activeTasks) {
        super(() -> performWork(work, deadlineNanos, durationMs, activeTasks));
        this.queuedAtNanos = queuedAtNanos;
        this.deadlineNanos = deadlineNanos;
        this.durationMs = durationMs;
        this.faultRevision = faultRevision;
    }

    private static <T> T performWork(Callable<T> work, long deadlineNanos,
                                     long durationMs, AtomicInteger activeTasks) throws Exception {
        checkDeadline(deadlineNanos);
        activeTasks.incrementAndGet();
        try {
            Thread.sleep(durationMs);
            checkDeadline(deadlineNanos);
            T result = work.call();
            checkDeadline(deadlineNanos);
            return result;
        } finally {
            activeTasks.decrementAndGet();
        }
    }

    private static void checkDeadline(long deadlineNanos) {
        if (Thread.currentThread().isInterrupted() || deadlineNanos - System.nanoTime() <= 0) {
            throw InventoryTaskExecutor.deadlineExceeded();
        }
    }
}
