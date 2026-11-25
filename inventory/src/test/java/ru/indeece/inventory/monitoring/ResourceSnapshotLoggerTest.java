package ru.indeece.inventory.monitoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import ru.indeece.inventory.execution.InventoryOperation;
import ru.indeece.inventory.execution.InventoryTaskExecutor;
import tools.jackson.databind.json.JsonMapper;

/**
 * Проверяет JSONL-формат, накопление строк и различимость перезапусков в одном файле.
 */
class ResourceSnapshotLoggerTest {
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @AfterEach
    void closeMeters() {
        meters.close();
    }

    @TempDir
    Path directory;

    @Test
    void appendsParseableSnapshotsAndPreservesExistingLines() throws Exception {
        Path file = directory.resolve("inventory.jsonl");
        meters.gauge("tomcat.threads.busy", Double.NaN);
        var mapper = JsonMapper.builder().build();
        try (var executor = new ThreadPoolExecutor(8, 8, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(16))) {
            InventoryTaskExecutor tasks = new InventoryTaskExecutor(executor);
            ResourceSnapshotLogger first = new ResourceSnapshotLogger(mapper, meters, tasks, file.toString(), "test-run");
            first.writeSnapshot();
            tasks.execute(InventoryOperation.AVAILABILITY, () -> "done");
            tasks.setFault(true);
            first.writeSnapshot();
            ResourceSnapshotLogger restarted = new ResourceSnapshotLogger(mapper, meters, tasks, file.toString(), "test-run");
            restarted.writeSnapshot();
            var lines = Files.readAllLines(file);
            assertEquals(3, lines.size());
            var initial = mapper.readTree(lines.get(0));
            var changed = mapper.readTree(lines.get(1));
            var afterRestart = mapper.readTree(lines.get(2));
            assertEquals("inventory", changed.path("service").asText());
            assertEquals("test-run", changed.path("runId").asText());
            assertEquals(2, changed.path("sequence").asInt());
            assertEquals(1, changed.path("inventory").path("accepted").asInt());
            assertEquals(0, changed.path("inventory").path("activeTasks").asInt());
            assertEquals(16, changed.path("inventory").path("queueCapacity").asInt());
            assertFalse(initial.path("inventory").path("fault").path("enabled").asBoolean());
            assertTrue(changed.path("inventory").path("fault").path("enabled").asBoolean());
            assertTrue(changed.path("servlet").path("tomcat.threads.busy").isNull());
            assertEquals(initial.path("instanceId"), changed.path("instanceId"));
            assertNotEquals(initial.path("instanceId"), afterRestart.path("instanceId"));
            assertEquals(1, afterRestart.path("sequence").asInt());
        }
    }

    @Test
    void invalidOutputPathFailsAtStartup() {
        try (var executor = new ThreadPoolExecutor(8, 8, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(16))) {
            assertThrows(IOException.class, () -> new ResourceSnapshotLogger(JsonMapper.builder().build(),
                    meters, new InventoryTaskExecutor(executor), directory.toString(), "test-run"));
        }
    }
}
