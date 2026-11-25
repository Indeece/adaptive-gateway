package ru.indeece.gateway.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayControllerTest {
    private static final HttpServer upstreamStub;
    private static final ExecutorService stubExecutor = Executors.newCachedThreadPool();
    private static final AtomicInteger upstreamCalls = new AtomicInteger();
    private static final HttpClient caller = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    private static volatile int upstreamStatus;
    private static volatile String upstreamBody;
    private static volatile String observedPath;
    private static volatile boolean holdResponse;
    private static volatile boolean disconnect;
    private static volatile boolean holdBody;
    private static volatile CountDownLatch bodyStarted = new CountDownLatch(0);
    private static volatile CountDownLatch releaseResponse = new CountDownLatch(0);

    static {
        try {
            upstreamStub = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            upstreamStub.setExecutor(stubExecutor);
            upstreamStub.createContext("/", exchange -> {
                upstreamCalls.incrementAndGet();
                observedPath = exchange.getRequestURI().toString();
                try {
                    if (holdResponse) {
                        releaseResponse.await(5, TimeUnit.SECONDS);
                    }
                    if (disconnect) {
                        return;
                    }
                    byte[] bytes = upstreamBody.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    if (upstreamStatus == 302) {
                        exchange.getResponseHeaders().set("Location", "/redirect-target");
                    }
                    exchange.sendResponseHeaders(upstreamStatus, bytes.length);
                    if (holdBody) {
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
    private RouteMetricsSnapshot beforeRequest;

    @BeforeEach
    void resetStub() {
        upstreamCalls.set(0);
        upstreamStatus = 200;
        upstreamBody = "{\"sku\":\"SKU-001\",\"available\":true,\"quantity\":10}";
        holdResponse = false;
        disconnect = false;
        holdBody = false;
        bodyStarted = new CountDownLatch(0);
        releaseResponse = new CountDownLatch(0);
        beforeRequest = metrics.snapshot().get(GatewayRoute.AVAILABILITY);
    }

    @AfterEach
    void verifyCompletedAccounting() {
        var afterRequest = metrics.snapshot().get(GatewayRoute.AVAILABILITY);
        assertEquals(0, afterRequest.active());
        assertEquals(upstreamCalls.get(), afterRequest.completed() - beforeRequest.completed());
        assertEquals(afterRequest.started(), afterRequest.completed());
    }

    @Test
    void countsCallAsActiveUntilTheWholeBodyIsRead() throws Exception {
        holdBody = true;
        bodyStarted = new CountDownLatch(1);
        releaseResponse = new CountDownLatch(1);
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/availability/SKU-001"))
                .timeout(Duration.ofSeconds(4)).GET().build();
        var response = caller.sendAsync(request, HttpResponse.BodyHandlers.ofString());
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

    private HttpResponse<String> request() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/availability/SKU-001"))
                .timeout(Duration.ofSeconds(4))
                .GET()
                .build();
        return caller.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void oneExpectedCallAndValidBody() throws Exception {
        HttpResponse<String> response = request();
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("SKU-001"));
        assertTrue(response.body().contains("\"available\":true"));
        assertEquals("/availability/SKU-001", observedPath);
        assertEquals(1, upstreamCalls.get());
    }

    @Test
    void preservesNotFoundAndInfrastructureErrors() throws Exception {
        upstreamStatus = 404;
        upstreamBody = "{\"code\":\"not_found\",\"message\":\"Unknown SKU\"}";
        assertEquals(404, request().statusCode());
        assertEquals(1, upstreamCalls.get());

        upstreamStatus = 503;
        upstreamBody = "{\"code\":\"inventory_overload\",\"message\":\"Busy\"}";
        HttpResponse<String> overload = request();
        assertEquals(503, overload.statusCode());
        assertTrue(overload.body().contains("inventory_overload"));
        assertEquals(2, upstreamCalls.get());

        upstreamStatus = 504;
        upstreamBody = "{\"code\":\"inventory_deadline\",\"message\":\"Deadline exceeded\"}";
        HttpResponse<String> deadline = request();
        assertEquals(504, deadline.statusCode());
        assertTrue(deadline.body().contains("inventory_deadline"));
        assertEquals(3, upstreamCalls.get());
    }

    @Test
    void timesOutWithoutRetryAndCanRecover() throws Exception {
        holdResponse = true;
        releaseResponse = new CountDownLatch(1);
        try {
            HttpResponse<String> response = request();
            assertEquals(504, response.statusCode());
            assertTrue(response.body().contains("upstream_timeout"));
            assertEquals(1, upstreamCalls.get());
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
        assertEquals(1, upstreamCalls.get());
    }

    @Test
    void connectionDropIsNotRetried() throws Exception {
        disconnect = true;
        assertEquals(502, request().statusCode());
        assertEquals(1, upstreamCalls.get());
    }

    @AfterAll
    static void stopStub() {
        releaseResponse.countDown();
        upstreamStub.stop(0);
        stubExecutor.shutdownNow();
        caller.close();
    }
}
