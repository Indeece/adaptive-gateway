package ru.indeece.inventory;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InventoryWorkPoolTest {
    @LocalServerPort
    private int port;

    @Autowired
    private ThreadPoolExecutor executor;

    @Autowired
    private InventoryWorkPool workPool;

    @Test
    void faultControlIsDisabledByDefault() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/internal/fault?enabled=true"))
                    .timeout(Duration.ofSeconds(3)).POST(HttpRequest.BodyPublishers.noBody()).build();
            assertEquals(404, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }

    @Test
    void bothRoutesReturnExpectedBodies() throws Exception {
        var availability = get("/availability/SKU-001");
        assertEquals(200, availability.statusCode());
        assertEquals("{\"sku\":\"SKU-001\",\"available\":true,\"quantity\":10}", availability.body());

        var batch = get("/internal/batch-availability?sku=SKU-001");
        assertEquals(200, batch.statusCode());
        assertEquals("{\"items\":[{\"sku\":\"SKU-001\",\"available\":true,\"quantity\":10}]}", batch.body());
    }

    @Test
    void bothRoutesRejectWhenSameEightWorkersAndSixteenQueueSlotsAreOccupied() throws Exception {
        try (var occupied = new OccupiedWorkers()) {
            CountDownLatch queuedFinished = new CountDownLatch(16);
            for (int index = 0; index < 16; index++) {
                executor.execute(queuedFinished::countDown);
            }
            assertEquals(8, executor.getCorePoolSize());
            assertEquals(8, executor.getMaximumPoolSize());
            assertEquals(8, executor.getActiveCount());
            assertEquals(16, executor.getQueue().size());
            assertEquals(0, executor.getQueue().remainingCapacity());

            assertError(get("/availability/SKU-001"), 503, "inventory_overload");
            assertError(get("/internal/batch-availability?sku=SKU-001"), 503, "inventory_overload");
            assertEquals(16, executor.getQueue().size());
            occupied.release();
            assertTrue(queuedFinished.await(5, TimeUnit.SECONDS));
        }
        assertEquals(200, get("/availability/SKU-001").statusCode());
    }

    @Test
    void unknownSkuIsStillNotFoundOnBothRoutes() throws Exception {
        assertError(get("/availability/UNKNOWN"), 404, "not_found");
        assertError(get("/internal/batch-availability?sku=UNKNOWN"), 404, "not_found");
    }

    @Test
    void queuedRequestTimesOutAndIsRemoved() throws Exception {
        try (var occupied = new OccupiedWorkers()) {
            assertError(get("/availability/SKU-001"), 504, "inventory_deadline");
            assertTrue(executor.getQueue().isEmpty());
        }
    }

    @Test
    void timeoutInterruptsRunningWork() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch exited = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            InventoryWorkException error = assertThrows(InventoryWorkException.class,
                    () -> workPool.execute(InventoryWorkPool.Operation.AVAILABILITY, () -> {
                        started.countDown();
                        try {
                            release.await();
                            return "unexpected";
                        } finally {
                            exited.countDown();
                        }
                    }));
            assertEquals(HttpStatus.GATEWAY_TIMEOUT, error.status());
            assertEquals("inventory_deadline", error.code());
            assertEquals(0, started.getCount());
            assertTrue(exited.await(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
        assertEquals("recovered", workPool.execute(InventoryWorkPool.Operation.AVAILABILITY, () -> "recovered"));
    }

    @Test
    void unexpectedTaskFailureRemainsVisible() {
        IllegalStateException original = new IllegalStateException("test failure");
        var actual = assertThrows(IllegalStateException.class, () -> workPool.execute(InventoryWorkPool.Operation.AVAILABILITY, () -> {
            throw original;
        }));
        assertSame(original, actual);
    }

    private HttpResponse<String> get(String path) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .timeout(Duration.ofSeconds(3)).GET().build();
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private void assertError(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode());
        assertTrue(response.body().contains("\"code\":\"" + code + "\""), response.body());
    }

    private class OccupiedWorkers implements AutoCloseable {
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch finished = new CountDownLatch(8);

        OccupiedWorkers() throws InterruptedException {
            CountDownLatch started = new CountDownLatch(8);
            for (int index = 0; index < 8; index++) {
                executor.execute(() -> {
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
            if (!started.await(5, TimeUnit.SECONDS)) {
                release();
                fail("Inventory workers did not start");
            }
        }

        void release() {
            release.countDown();
        }

        @Override
        public void close() throws InterruptedException {
            release();
            assertTrue(finished.await(5, TimeUnit.SECONDS));
        }
    }
}
