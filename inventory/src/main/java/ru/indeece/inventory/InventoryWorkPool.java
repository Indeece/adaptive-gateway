package ru.indeece.inventory;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class InventoryWorkPool {
    private static final long DEADLINE_NANOS = TimeUnit.MILLISECONDS.toNanos(450);
    private final ThreadPoolExecutor executor;
    private final Object admissionLock = new Object();
    private final AtomicInteger active = new AtomicInteger();
    private FaultState fault = new FaultState(false, System.currentTimeMillis(), 0);

    public enum Operation { AVAILABILITY, BATCH }

    public record FaultState(boolean enabled, long appliedEpochMs, long revision) {}

    public InventoryWorkPool(ThreadPoolExecutor executor) {
        this.executor = executor;
    }

    public FaultState setFault(boolean enabled) {
        synchronized (admissionLock) {
            fault = new FaultState(enabled, System.currentTimeMillis(), fault.revision() + 1);
            return fault;
        }
    }

    public int activeTasks() {
        return active.get();
    }

    public <T> T execute(Operation operation, Callable<T> work) {
        long queuedAtNanos = System.nanoTime();
        long deadline = queuedAtNanos + DEADLINE_NANOS;
        WorkTask<T> task;
        synchronized (admissionLock) {
            int durationMs = operation == Operation.AVAILABILITY ? 15 : (fault.enabled() ? 200 : 45);
            task = new WorkTask<>(work, queuedAtNanos, deadline, durationMs, fault.revision(), active);
            try {
                executor.execute(task);
            } catch (RejectedExecutionException exception) {
                throw new InventoryWorkException(HttpStatus.SERVICE_UNAVAILABLE,
                        "inventory_overload", "Inventory queue is full or executor is stopped");
            }
        }

        try {
            return task.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            cancel(task);
            throw deadlineExceeded();
        } catch (InterruptedException exception) {
            cancel(task);
            Thread.currentThread().interrupt();
            throw deadlineExceeded();
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof InterruptedException) {
                throw deadlineExceeded();
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Inventory task failed", cause);
        }
    }

    static final class WorkTask<T> extends FutureTask<T> {
        final long queuedAtNanos;
        final long deadlineNanos;
        final int durationMs;
        final long faultRevision;

        WorkTask(Callable<T> work, long queuedAtNanos, long deadlineNanos,
                 int durationMs, long faultRevision, AtomicInteger active) {
            super(() -> {
                checkDeadline(deadlineNanos);
                active.incrementAndGet();
                try {
                    Thread.sleep(durationMs);
                    checkDeadline(deadlineNanos);
                    T result = work.call();
                    checkDeadline(deadlineNanos);
                    return result;
                } finally {
                    active.decrementAndGet();
                }
            });
            this.queuedAtNanos = queuedAtNanos;
            this.deadlineNanos = deadlineNanos;
            this.durationMs = durationMs;
            this.faultRevision = faultRevision;
        }
    }

    private void cancel(FutureTask<?> task) {
        task.cancel(true);
        executor.remove(task);
    }

    private static void checkDeadline(long deadline) {
        if (Thread.currentThread().isInterrupted() || deadline - System.nanoTime() <= 0) {
            throw deadlineExceeded();
        }
    }

    private static InventoryWorkException deadlineExceeded() {
        return new InventoryWorkException(HttpStatus.GATEWAY_TIMEOUT,
                "inventory_deadline", "Inventory task deadline exceeded");
    }
}
