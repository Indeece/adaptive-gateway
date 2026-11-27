package ru.indeece.gateway.controller;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import ru.indeece.gateway.dto.RouteMetricsSnapshot;
import ru.indeece.gateway.monitoring.GatewayMetrics;
import ru.indeece.gateway.monitoring.GatewayRoute;

/**
 * Проверяет HTTP-маршрут и передачу ошибок соседнего сервиса через управляемую заглушку.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "admission.mode=B1",
        "admission.quotes-limit=1",
        "admission.queue-capacity=1",
        "admission.queue-wait=3s",
        "upstream.checkout.read-timeout=5s"
})
class GatewayControllerTest {
    private static final String AVAILABILITY_PATH = "/availability/SKU-001";
    private static final String QUOTE_PATH = "/quotes/SKU-001";
    private static final String QUOTE_BODY = """
            {"sku":"SKU-001","available":true,"price":100.00,"currency":"RUB"}
            """;
    private static final HttpServer upstreamStub;
    private static final ExecutorService stubExecutor = Executors.newCachedThreadPool();
    private static final AtomicInteger availabilityCalls = new AtomicInteger();
    private static final AtomicInteger checkoutCalls = new AtomicInteger();
    private static final HttpClient caller = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    private static volatile int upstreamStatus;
    private static volatile String upstreamBody;
    private static volatile String observedPath;
    private static volatile boolean holdResponse;
    private static volatile boolean disconnect;
    private static volatile String heldBodyPath;
    private static volatile CountDownLatch bodyStarted = new CountDownLatch(0);
    private static volatile CountDownLatch releaseResponse = new CountDownLatch(0);

    static {
        try {
            upstreamStub = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            upstreamStub.setExecutor(stubExecutor);
            upstreamStub.createContext("/", exchange -> {
                String path = exchange.getRequestURI().toString();
                boolean quoteCall = path.startsWith("/quotes/");
                if (quoteCall) {
                    checkoutCalls.incrementAndGet();
                } else {
                    availabilityCalls.incrementAndGet();
                }
                observedPath = path;
                try {
                    if (holdResponse) {
                        releaseResponse.await(5, TimeUnit.SECONDS);
                    }
                    if (disconnect) {
                        return;
                    }
                    String body = upstreamBody;
                    if (quoteCall && upstreamStatus == 200) {
                        body = QUOTE_BODY;
                    }
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    if (upstreamStatus == 302) {
                        exchange.getResponseHeaders().set("Location", "/redirect-target");
                    }
                    exchange.sendResponseHeaders(upstreamStatus, bytes.length);
                    if (path.equals(heldBodyPath)) {
                        exchange.getResponseBody().write(bytes, 0, 1);
                        exchange.getResponseBody().flush();
                        bodyStarted.countDown();
                        releaseResponse.await(5, TimeUnit.SECONDS);
                        exchange.getResponseBody().write(bytes, 1, bytes.length - 1);
                        return;
                    }
                    exchange.getResponseBody().write(bytes);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } finally {
                    exchange.close();
                }
            });
            upstreamStub.start();
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @DynamicPropertySource
    static void upstreamUrls(DynamicPropertyRegistry registry) {
        registry.add("upstream.inventory.base-url", () -> "http://localhost:" + upstreamStub.getAddress().getPort());
        registry.add("upstream.checkout.base-url", () -> "http://localhost:" + upstreamStub.getAddress().getPort());
    }

    @LocalServerPort
    private int port;

    @Autowired
    private GatewayMetrics metrics;
    @Autowired
    private MeterRegistry meters;
    private RouteMetricsSnapshot beforeRequest;

    @BeforeEach
    void resetStub() {
        availabilityCalls.set(0);
        checkoutCalls.set(0);
        upstreamStatus = 200;
        upstreamBody = "{\"sku\":\"SKU-001\",\"available\":true,\"quantity\":10}";
        holdResponse = false;
        disconnect = false;
        heldBodyPath = null;
        bodyStarted = new CountDownLatch(0);
        releaseResponse = new CountDownLatch(0);
        beforeRequest = metrics.snapshot().get(GatewayRoute.AVAILABILITY);
    }

    @AfterEach
    void verifyCompletedAccounting() {
        var afterRequest = metrics.snapshot().get(GatewayRoute.AVAILABILITY);
        assertEquals(0, afterRequest.active());
        assertEquals(availabilityCalls.get(), afterRequest.completed() - beforeRequest.completed());
        assertEquals(afterRequest.started(), afterRequest.completed());
    }

    @Test
    void countsCallAsActiveUntilTheWholeBodyIsRead() throws Exception {
        heldBodyPath = AVAILABILITY_PATH;
        bodyStarted = new CountDownLatch(1);
        releaseResponse = new CountDownLatch(1);
        var response = caller.sendAsync(newRequest(AVAILABILITY_PATH), HttpResponse.BodyHandlers.ofString());
        try {
            assertTrue(bodyStarted.await(5, TimeUnit.SECONDS));
            var pending = metrics.snapshot().get(GatewayRoute.AVAILABILITY);
            assertEquals(1, pending.active());
            assertEquals(beforeRequest.completed(), pending.completed());
        } finally {
            releaseResponse.countDown();
        }
        assertEquals(200, response.get(4, TimeUnit.SECONDS).statusCode());
    }

    @Test
    void queuesQuoteUntilSlotReleasesAndOnlyRefusesWhenQueueIsFull() throws Exception {
        var beforeQuote = metrics.snapshot().get(GatewayRoute.QUOTES);
        heldBodyPath = QUOTE_PATH;
        bodyStarted = new CountDownLatch(1);
        releaseResponse = new CountDownLatch(1);
        var queuedQuotes = meters.get("gateway.quotes.queued").gauge();
        var activeQuotes = meters.get("gateway.quotes.admitted").gauge();
        var admitted = caller.sendAsync(newRequest(QUOTE_PATH), HttpResponse.BodyHandlers.ofString());
        CompletableFuture<HttpResponse<String>> queued;
        try {
            assertTrue(bodyStarted.await(5, TimeUnit.SECONDS));
            queued = caller.sendAsync(newRequest(QUOTE_PATH), HttpResponse.BodyHandlers.ofString());
            await().atMost(Duration.ofSeconds(2)).until(() -> queuedQuotes.value() == 1);
            assertEquals(1, checkoutCalls.get());
            assertEquals(1.0, activeQuotes.value());

            var rejected = caller.send(newRequest(QUOTE_PATH), HttpResponse.BodyHandlers.ofString());
            assertEquals(503, rejected.statusCode());
            assertTrue(rejected.body().contains("\"code\":\"gateway_overload\""));
            assertEquals(1, checkoutCalls.get());

            assertEquals(200, request().statusCode());
            assertFalse(admitted.isDone());
            assertFalse(queued.isDone());
        } finally {
            releaseResponse.countDown();
        }

        assertEquals(200, admitted.get(4, TimeUnit.SECONDS).statusCode());
        assertEquals(200, queued.get(4, TimeUnit.SECONDS).statusCode());
        assertEquals(2, checkoutCalls.get());
        assertEquals(0.0, activeQuotes.value());
        assertEquals(0.0, queuedQuotes.value());

        var afterQuote = metrics.snapshot().get(GatewayRoute.QUOTES);
        assertEquals(3, afterQuote.started() - beforeQuote.started());
        assertEquals(3, afterQuote.completed() - beforeQuote.completed());
        assertEquals(0, afterQuote.active());
        assertEquals(1L, afterQuote.outcomes().getOrDefault("gateway_overload", 0L)
                - beforeQuote.outcomes().getOrDefault("gateway_overload", 0L));
    }

    private HttpRequest newRequest(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(4))
                .GET()
                .build();
    }

    private HttpResponse<String> request() throws Exception {
        return caller.send(newRequest(AVAILABILITY_PATH), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void oneExpectedCallAndValidBody() throws Exception {
        HttpResponse<String> response = request();
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("SKU-001"));
        assertTrue(response.body().contains("\"available\":true"));
        assertEquals("/availability/SKU-001", observedPath);
        assertEquals(1, availabilityCalls.get());
    }

    @Test
    void preservesNotFoundAndInfrastructureErrors() throws Exception {
        upstreamStatus = 404;
        upstreamBody = "{\"code\":\"not_found\",\"message\":\"Unknown SKU\"}";
        assertEquals(404, request().statusCode());
        assertEquals(1, availabilityCalls.get());

        upstreamStatus = 503;
        upstreamBody = "{\"code\":\"inventory_overload\",\"message\":\"Busy\"}";
        HttpResponse<String> overload = request();
        assertEquals(503, overload.statusCode());
        assertTrue(overload.body().contains("inventory_overload"));
        assertEquals(2, availabilityCalls.get());

        upstreamStatus = 504;
        upstreamBody = "{\"code\":\"inventory_deadline\",\"message\":\"Deadline exceeded\"}";
        HttpResponse<String> deadline = request();
        assertEquals(504, deadline.statusCode());
        assertTrue(deadline.body().contains("inventory_deadline"));
        assertEquals(3, availabilityCalls.get());
    }

    @Test
    void timesOutWithoutRetryAndCanRecover() throws Exception {
        holdResponse = true;
        releaseResponse = new CountDownLatch(1);
        try {
            HttpResponse<String> response = request();
            assertEquals(504, response.statusCode());
            assertTrue(response.body().contains("upstream_timeout"));
            assertEquals(1, availabilityCalls.get());
        } finally {
            holdResponse = false;
            releaseResponse.countDown();
        }
        assertEquals(200, request().statusCode());
    }

    @Test
    void doesNotFollowRedirects() throws Exception {
        upstreamStatus = 302;
        upstreamBody = "{}";
        assertEquals(502, request().statusCode());
        assertEquals(1, availabilityCalls.get());
    }

    @Test
    void connectionDropIsNotRetried() throws Exception {
        disconnect = true;
        assertEquals(502, request().statusCode());
        assertEquals(1, availabilityCalls.get());
    }

    @AfterAll
    static void stopStub() {
        releaseResponse.countDown();
        upstreamStub.stop(0);
        stubExecutor.shutdownNow();
        caller.close();
    }
}
