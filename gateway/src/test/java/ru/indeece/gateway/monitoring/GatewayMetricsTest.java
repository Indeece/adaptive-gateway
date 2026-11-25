package ru.indeece.gateway.monitoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

/**
 * Проверяет согласованность учёта маршрутов при параллельных вызовах и ошибках.
 */
class GatewayMetricsTest {
    @Test
    void tracksBothRoutesWhileCallsAreInProgress() throws Exception {
        GatewayMetrics metrics = new GatewayMetrics();
        CountDownLatch started = new CountDownLatch(6);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(6)) {
            var responses = new ArrayList<Future<String>>();
            try {
                for (int index = 0; index < 6; index++) {
                    GatewayRoute route = index < 4 ? GatewayRoute.QUOTES : GatewayRoute.AVAILABILITY;
                    responses.add(executor.submit(() -> metrics.measure(route, () -> {
                        started.countDown();
                        try {
                            assertTrue(release.await(5, TimeUnit.SECONDS));
                        } catch (InterruptedException exception) {
                            throw new AssertionError(exception);
                        }
                        return "done";
                    })));
                }
                assertTrue(started.await(5, TimeUnit.SECONDS));
                var pending = metrics.snapshot();
                assertEquals(4, pending.get(GatewayRoute.QUOTES).active());
                assertEquals(2, pending.get(GatewayRoute.AVAILABILITY).active());
                assertEquals(0, pending.get(GatewayRoute.QUOTES).completed());
            } finally {
                release.countDown();
            }
            for (Future<String> response : responses) {
                assertEquals("done", response.get(5, TimeUnit.SECONDS));
            }
        }
        var snapshot = metrics.snapshot();
        assertEquals(4, snapshot.get(GatewayRoute.QUOTES).completed());
        assertEquals(2, snapshot.get(GatewayRoute.AVAILABILITY).completed());
        for (var route : snapshot.values()) {
            assertEquals(0, route.active());
            assertEquals(route.started(), route.completed());
            assertEquals(route.completed(), route.outcomes().get("success"));
            assertTrue(route.totalDurationMs() >= route.maxDurationMs());
        }
    }

    @Test
    void errorsAreCountedAndRethrown() {
        GatewayMetrics metrics = new GatewayMetrics();
        assertThrows(HttpClientErrorException.class, () -> metrics.measure(GatewayRoute.QUOTES, () -> {
            throw new HttpClientErrorException(HttpStatus.NOT_FOUND);
        }));
        assertThrows(RestClientException.class, () -> metrics.measure(GatewayRoute.QUOTES, () -> {
            throw new RestClientException("connection failed");
        }));
        assertThrows(AssertionError.class, () -> metrics.measure(GatewayRoute.QUOTES, () -> {
            throw new AssertionError("unexpected failure");
        }));
        var snapshot = metrics.snapshot().get(GatewayRoute.QUOTES);
        assertEquals(3, snapshot.started());
        assertEquals(3, snapshot.completed());
        assertEquals(0, snapshot.active());
        assertEquals(1L, snapshot.outcomes().get("http_404"));
        assertEquals(1L, snapshot.outcomes().get("transport_error"));
        assertEquals(1L, snapshot.outcomes().get("unexpected_error"));
    }
}
