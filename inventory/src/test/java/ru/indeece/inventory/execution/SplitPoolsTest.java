package ru.indeece.inventory.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Проверяет диагностическую изоляцию availability при занятых шести batch-воркерах.
 */
@SpringBootTest(properties = "inventory.split-pools=true")
class SplitPoolsTest {
    @Autowired
    private InventoryTaskExecutor tasks;
    @Autowired
    @Qualifier("inventoryExecutor")
    private ThreadPoolExecutor batchPool;

    @Test
    void availabilityUsesItsOwnTwoWorkers() throws Exception {
        CountDownLatch started = new CountDownLatch(6);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(6);
        try {
            for (int index = 0; index < 6; index++) {
                batchPool.execute(() -> {
                    started.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    } finally {
                        finished.countDown();
                    }
                });
            }
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertEquals("available", tasks.execute(InventoryOperation.AVAILABILITY, () -> "available"));
            var snapshot = tasks.snapshot();
            assertEquals(8, snapshot.workerLimit());
            assertEquals(16, snapshot.queueCapacity());
            assertEquals(6, snapshot.pools().get("batch").busyWorkers());
            assertEquals(12, snapshot.pools().get("batch").queueCapacity());
            assertEquals(2, snapshot.pools().get("availability").workerLimit());
            assertEquals(4, snapshot.pools().get("availability").queueCapacity());
        } finally {
            release.countDown();
            assertTrue(finished.await(5, TimeUnit.SECONDS));
        }
    }
}
