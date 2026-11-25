package ru.indeece.inventory.execution;

import java.util.concurrent.Callable;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import ru.indeece.inventory.dto.ExecutorResourceSnapshot;
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
    private final ThreadPoolExecutor availabilityExecutor;
    private final Object admissionLock = new Object();
    private final AtomicInteger activeTasks = new AtomicInteger();
    private final AtomicLong expiredRequests = new AtomicLong();
    private final int queueCapacity;
    private long accepted;
    private long rejected;
    private BatchFaultState fault = new BatchFaultState(false, System.currentTimeMillis(), 0);

    public InventoryTaskExecutor(ThreadPoolExecutor executor) {
        this(executor, executor);
    }

    @Autowired
    public InventoryTaskExecutor(ThreadPoolExecutor executor,
            @Qualifier("availabilityExecutor") ObjectProvider<ThreadPoolExecutor> availability) {
        this(executor, availability.getIfAvailable(() -> executor));
    }

    InventoryTaskExecutor(ThreadPoolExecutor executor, ThreadPoolExecutor availabilityExecutor) {
        this.executor = executor;
        this.availabilityExecutor = availabilityExecutor;
        this.queueCapacity = capacity(executor)
                + (executor == availabilityExecutor ? 0 : capacity(availabilityExecutor));
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
            boolean shared = executor == availabilityExecutor;
            Map<String, ExecutorResourceSnapshot> pools = shared
                    ? Map.of("shared", poolSnapshot(executor))
                    : Map.of("batch", poolSnapshot(executor), "availability", poolSnapshot(availabilityExecutor));
            return new InventoryResourceSnapshot(activeTasks.get(), executor.getQueue().size()
                    + (shared ? 0 : availabilityExecutor.getQueue().size()),
                    executor.getMaximumPoolSize() + (shared ? 0 : availabilityExecutor.getMaximumPoolSize()),
                    queueCapacity, accepted, rejected, expiredRequests.get(),
                    executor.getCompletedTaskCount() + (shared ? 0 : availabilityExecutor.getCompletedTaskCount()),
                    fault, pools);
        }
    }

    private static int capacity(ThreadPoolExecutor pool) {
        return pool.getQueue().size() + pool.getQueue().remainingCapacity();
    }

    private static ExecutorResourceSnapshot poolSnapshot(ThreadPoolExecutor pool) {
        return new ExecutorResourceSnapshot(pool.getActiveCount(), pool.getQueue().size(),
                pool.getMaximumPoolSize(), capacity(pool));
    }

    public <T> T execute(InventoryOperation operation, Callable<T> work) {
        long queuedAtNanos = System.nanoTime();
        long deadline = queuedAtNanos + DEADLINE_NANOS;
        InventoryTask<T> task;
        ThreadPoolExecutor selectedPool = operation == InventoryOperation.AVAILABILITY ? availabilityExecutor : executor;
        synchronized (admissionLock) {
            int durationMs = switch (operation) {
                case AVAILABILITY -> 15;
                case BATCH -> fault.enabled() ? 200 : 45;
            };
            task = new InventoryTask<>(work, queuedAtNanos, deadline, durationMs, fault.revision(), activeTasks);
            try {
                selectedPool.execute(task);
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
            cancel(task, selectedPool);
            expiredRequests.incrementAndGet();
            throw deadlineExceeded();
        } catch (InterruptedException exception) {
            cancel(task, selectedPool);
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

    private void cancel(FutureTask<?> task, ThreadPoolExecutor pool) {
        task.cancel(true);
        pool.remove(task);
    }

    static InventoryTaskException deadlineExceeded() {
        return new InventoryTaskException(HttpStatus.GATEWAY_TIMEOUT,
                "inventory_deadline", "Inventory task deadline exceeded");
    }
}
