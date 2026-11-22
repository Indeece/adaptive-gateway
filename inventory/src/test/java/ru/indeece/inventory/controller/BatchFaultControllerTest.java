package ru.indeece.inventory.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Проверяет HTTP-команды смены профиля и серверную отметку применения.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "inventory.fault-control.enabled=true")
class BatchFaultControllerTest {
    @LocalServerPort
    private int port;

    @Test
    void togglesFaultAndReturnsApplicationTime() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            long before = System.currentTimeMillis();
            var enabled = post(client, "?enabled=true");
            long after = System.currentTimeMillis();
            assertEquals(200, enabled.statusCode());
            assertTrue(enabled.body().contains("\"enabled\":true"));
            var timestamp = java.util.regex.Pattern.compile("\"appliedEpochMs\":(\\d+)").matcher(enabled.body());
            assertTrue(timestamp.find());
            long applied = Long.parseLong(timestamp.group(1));
            assertTrue(applied >= before && applied <= after);
            assertTrue(enabled.body().contains("\"revision\":1"));
            var disabled = post(client, "?enabled=false");
            assertEquals(200, disabled.statusCode());
            assertTrue(disabled.body().contains("\"enabled\":false"));
            assertTrue(disabled.body().contains("\"revision\":2"));
            assertEquals(400, post(client, "").statusCode());
            assertEquals(400, post(client, "?enabled=invalid").statusCode());
        }
    }

    private HttpResponse<String> post(HttpClient client, String query) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/internal/fault" + query))
                .timeout(Duration.ofSeconds(3)).POST(HttpRequest.BodyPublishers.noBody()).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
