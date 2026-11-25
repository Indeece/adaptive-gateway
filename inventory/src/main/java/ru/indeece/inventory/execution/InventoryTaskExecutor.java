package ru.indeece.inventory.execution;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import ru.indeece.inventory.dto.BatchFaultState;
import ru.indeece.inventory.dto.InventoryResourceSnapshot;
import ru.indeece.inventory.error.InventoryTaskException;

/**
 * Согласует приём задач с профилем замедления и ограничивает ожидание их результата.
 */
@Service
public class InventoryTaskExecutor {
    private static final long DEADLINE_NANOS = TimeUnit.MILLISECONDS.toNanos(450);
    private final ThreadPoolExecutor executor;
    private final Object admissionLock = new Object();
    private final AtomicInteger activeTasks = new AtomicInteger();
    private final AtomicLong expiredRequests = new AtomicLong();
    private final int queueCapacity;
    private long accepted;
    private long rejected;
    private BatchFaultState fault = new BatchFaultState(false, System.currentTimeMillis(), 0);

    public InventoryTaskExecutor(ThreadPoolExecutor executor) {
        this.executor = executor;
        this.queueCapacity = executor.getQueue().size() + executor.getQueue().remainingCapacity();
    }

    public BatchFaultState setFault(boolean enabled) {
        synchronized (admissionLock) {
            fault = new BatchFaultState(enabled, System.currentTimeMillis(), fault.revision() + 1);
            return fault;
        }
    }

    public int activeTasks() {
        return activeTasks.get();
    }

    public InventoryResourceSnapshot snapshot() {
        synchronized (admissionLock) {
            return new InventoryResourceSnapshot(activeTasks.get(), executor.getQueue().size(),
                    executor.getMaximumPoolSize(), queueCapacity, accepted, rejected,
                    expiredRequests.get(), executor.getCompletedTaskCount(), fault);
        }
    }

    public <T> T execute(InventoryOperation operation, Callable<T> work) {
        long queuedAtNanos = System.nanoTime();
        long deadline = queuedAtNanos + DEADLINE_NANOS;
        InventoryTask<T> task;
        synchronized (admissionLock) {
            int durationMs = switch (operation) {
                case AVAILABILITY -> 15;
                case BATCH -> fault.enabled() ? 200 : 45;
            };
            task = new InventoryTask<>(work, queuedAtNanos, deadline, durationMs, fault.revision(), activeTasks);
            try {
                executor.execute(task);
                accepted++;
            } catch (RejectedExecutionException exception) {
                rejected++;
                throw new InventoryTaskException(HttpStatus.SERVICE_UNAVAILABLE,
                        "inventory_overload", "Inventory queue is full or executor is stopped");
            }
        }

        try {
            return task.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            cancel(task);
            expiredRequests.incrementAndGet();
            throw deadlineExceeded();
        } catch (InterruptedException exception) {
            cancel(task);
            expiredRequests.incrementAndGet();
            Thread.currentThread().interrupt();
            throw deadlineExceeded();
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof InventoryTaskException taskException
                    && "inventory_deadline".equals(taskException.code())) {
                expiredRequests.incrementAndGet();
            }
            if (cause instanceof InterruptedException) {
                expiredRequests.incrementAndGet();
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

    private void cancel(FutureTask<?> task) {
        task.cancel(true);
        executor.remove(task);
    }

    static InventoryTaskException deadlineExceeded() {
        return new InventoryTaskException(HttpStatus.GATEWAY_TIMEOUT,
                "inventory_deadline", "Inventory task deadline exceeded");
    }
}
