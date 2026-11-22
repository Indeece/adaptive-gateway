package ru.indeece.inventory;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class InventoryWorkPool {
    private static final long DEADLINE_NANOS = TimeUnit.MILLISECONDS.toNanos(450);
    private final ThreadPoolExecutor executor;

    public InventoryWorkPool(ThreadPoolExecutor executor) {
        this.executor = executor;
    }

    public <T> T execute(Callable<T> work) {
        long deadline = System.nanoTime() + DEADLINE_NANOS;
        FutureTask<T> task = new FutureTask<>(() -> {
            checkDeadline(deadline);
            T result = work.call();
            checkDeadline(deadline);
            return result;
        });
        try {
            executor.execute(task);
        } catch (RejectedExecutionException exception) {
            throw new InventoryWorkException(HttpStatus.SERVICE_UNAVAILABLE,
                    "inventory_overload", "Inventory queue is full or executor is stopped");
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
