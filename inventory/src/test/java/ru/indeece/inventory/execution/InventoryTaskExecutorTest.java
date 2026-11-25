package ru.indeece.inventory.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ru.indeece.inventory.execution.InventoryOperation.AVAILABILITY;
import static ru.indeece.inventory.execution.InventoryOperation.BATCH;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import ru.indeece.inventory.error.InventoryTaskException;

/**
 * Проверяет дедлайны, отмену, учёт работы и порядок переключения профиля задач.
 */
class InventoryTaskExecutorTest {
    private final RecordingExecutor executor = new RecordingExecutor();
    private final InventoryTaskExecutor pool = new InventoryTaskExecutor(executor);
    private final ExecutorService callers = Executors.newFixedThreadPool(2);
    private final CountDownLatch releaseWorker = new CountDownLatch(1);

    @AfterEach
    void stop() throws InterruptedException {
        releaseWorker.countDown();
        executor.releaseAdmission.countDown();
        callers.shutdownNow();
        executor.shutdownNow();
        assertTrue(callers.awaitTermination(5, TimeUnit.SECONDS));
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(0, pool.activeTasks());
    }

    @Test
    void queuedBatchKeepsFaultProfileAfterFaultOff() throws Exception {
        occupyWorker();
        var enabled = pool.setFault(true);
        var response = callers.submit(() -> pool.execute(BATCH, () -> "done"));
        assertTrue(executor.admitted.await(5, TimeUnit.SECONDS));
        var task = executor.lastTask;
        assertEquals(200, task.durationMs);
        assertEquals(enabled.revision(), task.faultRevision);
        assertEquals(TimeUnit.MILLISECONDS.toNanos(450), task.deadlineNanos - task.queuedAtNanos);
        pool.setFault(false);
        assertEquals(200, task.durationMs);
        releaseWorker.countDown();
        assertEquals("done", response.get(3, TimeUnit.SECONDS));
        assertEquals("normal", pool.execute(BATCH, () -> "normal"));
        assertEquals(45, executor.lastTask.durationMs);
        pool.setFault(true);
        pool.execute(AVAILABILITY, () -> "short");
        assertEquals(15, executor.lastTask.durationMs);
    }

    @Test
    void faultSwitchAndAdmissionHaveOneOrder() throws Exception {
        occupyWorker();
        executor.holdAdmission = true;
        var response = callers.submit(() -> pool.execute(BATCH, () -> "done"));
        assertTrue(executor.admitted.await(5, TimeUnit.SECONDS));
        CountDownLatch switching = new CountDownLatch(1);
        var change = callers.submit(() -> {
            switching.countDown();
            return pool.setFault(true);
        });
        assertTrue(switching.await(5, TimeUnit.SECONDS));
        assertFalse(change.isDone());
        assertEquals(45, executor.lastTask.durationMs);
        assertEquals(0, executor.lastTask.faultRevision);
        executor.releaseAdmission.countDown();
        assertEquals(1, change.get(3, TimeUnit.SECONDS).revision());
        releaseWorker.countDown();
        assertEquals("done", response.get(3, TimeUnit.SECONDS));
        pool.execute(BATCH, () -> "next");
        assertEquals(200, executor.lastTask.durationMs);
        assertEquals(1, executor.lastTask.faultRevision);
    }

    @Test
    void timedOutQueuedTaskNeverRunsAndQueueSpaceIsRecovered() throws Exception {
        occupyWorker();
        AtomicBoolean ran = new AtomicBoolean();
        var response = callers.submit(() -> pool.execute(BATCH, () -> ran.getAndSet(true)));
        assertTrue(executor.admitted.await(5, TimeUnit.SECONDS));
        var error = assertThrows(ExecutionException.class, () -> response.get(3, TimeUnit.SECONDS));
        assertEquals("inventory_deadline", ((InventoryTaskException) error.getCause()).code());
        assertTrue(executor.lastTask.isCancelled());
        assertTrue(executor.getQueue().isEmpty());
        releaseWorker.countDown();
        executor.submit(() -> {}).get(3, TimeUnit.SECONDS);
        assertFalse(ran.get());
        assertEquals(0, pool.activeTasks());
    }

    @Test
    void expiredTaskSkipsWorkEvenBeforeWaitingCallerCancelsIt() {
        AtomicBoolean ran = new AtomicBoolean();
        AtomicInteger active = new AtomicInteger();
        long expired = System.nanoTime() - 1;
        var task = new InventoryTask<>(() -> ran.getAndSet(true),
                expired - 450_000_000L, expired, 15, 0, active);
        task.run();
        var error = assertThrows(ExecutionException.class, task::get);
        assertEquals("inventory_deadline", ((InventoryTaskException) error.getCause()).code());
        assertFalse(ran.get());
        assertEquals(0, active.get());
    }

    @Test
    void cancellingRunningTaskTracksActiveUntilWorkActuallyExits() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        var response = callers.submit(() -> pool.execute(BATCH, () -> {
            entered.countDown();
            try {
                releaseWorker.await();
            } catch (InterruptedException exception) {
                interrupted.countDown();
                // Hold the task here to verify that cancellation alone cannot decrement active.
                releaseWorker.await();
                throw exception;
            }
            return "unexpected";
        }));
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertEquals(1, pool.activeTasks());
        assertThrows(ExecutionException.class, () -> response.get(3, TimeUnit.SECONDS));
        assertTrue(interrupted.await(5, TimeUnit.SECONDS));
        assertEquals(1, pool.activeTasks());
        releaseWorker.countDown();
        executor.submit(() -> {}).get(3, TimeUnit.SECONDS);
        assertEquals(0, pool.activeTasks());
    }

    @Test
    void interruptedCallerCancelsQueuedWorkAndKeepsInterruptFlag() throws Exception {
        occupyWorker();
        AtomicBoolean ran = new AtomicBoolean();
        AtomicBoolean keptInterrupt = new AtomicBoolean();
        AtomicBoolean deadlineError = new AtomicBoolean();
        Thread caller = new Thread(() -> {
            try {
                pool.execute(BATCH, () -> ran.getAndSet(true));
            } catch (InventoryTaskException error) {
                deadlineError.set("inventory_deadline".equals(error.code()));
                keptInterrupt.set(Thread.currentThread().isInterrupted());
            }
        });
        caller.start();
        try {
            assertTrue(executor.admitted.await(5, TimeUnit.SECONDS));
            caller.interrupt();
            caller.join(3000);
            assertFalse(caller.isAlive());
            assertTrue(deadlineError.get());
            assertTrue(keptInterrupt.get());
            assertTrue(executor.getQueue().isEmpty());
            releaseWorker.countDown();
            executor.submit(() -> {}).get(3, TimeUnit.SECONDS);
            assertFalse(ran.get());
        } finally {
            caller.interrupt();
            caller.join(3000);
        }
    }

    private void occupyWorker() throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        executor.execute(() -> {
            started.countDown();
            try {
                releaseWorker.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));
    }

    /**
     * Сохраняет принятую задачу и управляет моментом завершения её постановки в тесте.
     */
    private static class RecordingExecutor extends ThreadPoolExecutor {
        final CountDownLatch admitted = new CountDownLatch(1);
        final CountDownLatch releaseAdmission = new CountDownLatch(1);
        volatile InventoryTask<?> lastTask;
        volatile boolean holdAdmission;

        RecordingExecutor() {
            super(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16), new AbortPolicy());
        }

        @Override
        public void execute(Runnable task) {
            super.execute(task);
            if (task instanceof InventoryTask<?> workTask) {
                lastTask = workTask;
                admitted.countDown();
                if (holdAdmission) {
                    try {
                        assertTrue(releaseAdmission.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(exception);
                    }
                }
            }
        }
    }
}
