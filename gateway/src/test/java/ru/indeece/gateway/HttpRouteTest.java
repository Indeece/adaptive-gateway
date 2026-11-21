package ru.indeece.gateway;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HttpRouteTest {
    static final HttpServer stub;
    static final ExecutorService executor = Executors.newCachedThreadPool();
    static final AtomicInteger calls = new AtomicInteger();
    static volatile int status;
    static volatile String body;
    static volatile String observedPath;
    static volatile boolean hang;
    static volatile boolean disconnect;
    static volatile CountDownLatch release = new CountDownLatch(0);
    static {
        try {
            stub = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            stub.setExecutor(executor);
            stub.createContext("/", exchange -> {
                calls.incrementAndGet();
                observedPath = exchange.getRequestURI().toString();
                try {
                    if (hang) release.await(5, TimeUnit.SECONDS);
                    if (disconnect) return;
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    if (status == 302) exchange.getResponseHeaders().set("Location", "/redirect-target");
                    exchange.sendResponseHeaders(status, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                finally { exchange.close(); }
            });
            stub.start();
        } catch (Exception error) { throw new ExceptionInInitializerError(error); }
    }
    @DynamicPropertySource
    static void urls(DynamicPropertyRegistry registry) {
        registry.add("upstream.inventory.base-url", () -> "http://localhost:" + stub.getAddress().getPort());
        registry.add("upstream.checkout.base-url", () -> "http://localhost:" + stub.getAddress().getPort());
    }
    @LocalServerPort int port;
    final HttpClient caller = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(2)).build();
    @BeforeEach void reset() {
        calls.set(0); status = 200; body = "{\"sku\":\"SKU-001\",\"available\":true,\"quantity\":10}";
        hang = false; disconnect = false; release = new CountDownLatch(0);
    }
    HttpResponse<String> request() throws Exception {
        return caller.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/availability/SKU-001"))
                .timeout(java.time.Duration.ofSeconds(4)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    @Test void oneExpectedCallAndValidBody() throws Exception {
        var response = request();
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("SKU-001"));
        assertTrue(response.body().contains("\"available\":true"));
        assertEquals("/availability/SKU-001", observedPath);
        assertEquals(1, calls.get());
    }
    @Test void preservesNotFoundAndInfrastructureError() throws Exception {
        status = 404; body = "{\"code\":\"not_found\",\"message\":\"Unknown SKU\"}";
        assertEquals(404, request().statusCode());
        assertEquals(1, calls.get());
        status = 503; body = "{\"code\":\"inventory_overload\",\"message\":\"Busy\"}";
        var response = request();
        assertEquals(503, response.statusCode());
        assertTrue(response.body().contains("inventory_overload"));
        assertEquals(2, calls.get());
    }
    @Test void timesOutWithoutRetryAndCanRecover() throws Exception {
        hang = true; release = new CountDownLatch(1);
        try {
            var response = request();
            assertEquals(504, response.statusCode());
            assertTrue(response.body().contains("upstream_timeout"));
            assertEquals(1, calls.get());
        } finally { hang = false; release.countDown(); }
        assertEquals(200, request().statusCode());
    }
    @Test void doesNotFollowRedirects() throws Exception {
        status = 302; body = "{}";
        assertEquals(502, request().statusCode());
        assertEquals(1, calls.get());
    }
    @Test void connectionDropIsNotRetried() throws Exception {
        disconnect = true;
        assertEquals(502, request().statusCode());
        assertEquals(1, calls.get());
    }
    @AfterAll static void stop() { release.countDown(); stub.stop(0); executor.shutdownNow(); }
}
