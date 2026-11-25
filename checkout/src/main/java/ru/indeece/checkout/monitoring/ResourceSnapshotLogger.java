package ru.indeece.checkout.monitoring;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.SerializationFeature;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.pool.PoolStats;
import ru.indeece.checkout.config.ObservabilityProperties;

/**
 * Сохраняет секундные JSONL-снимки ресурсов checkout с временем и идентификатором процесса.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "observability.snapshots.enabled", havingValue = "true")
public class ResourceSnapshotLogger {
    private final ObjectWriter jsonWriter;
    private final MeterRegistry meters;
    private final Path file;
    private final String runId;
    private final String instanceId = UUID.randomUUID().toString();
    private final long startedAtNanos = System.nanoTime();
    private long sequence;
    private final PoolingHttpClientConnectionManager connectionPool;

    public ResourceSnapshotLogger(ObjectMapper mapper, MeterRegistry meters, PoolingHttpClientConnectionManager connectionPool,
            ObservabilityProperties settings) throws IOException {
        this.jsonWriter = mapper.writer().without(SerializationFeature.INDENT_OUTPUT);
        this.meters = meters;
        this.file = settings.snapshots().file().toAbsolutePath();
        this.runId = settings.runId();
        this.connectionPool = connectionPool;
        Files.createDirectories(this.file.getParent());
        try (var writer = Files.newBufferedWriter(this.file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            writer.flush();
        }
    }

    @Scheduled(fixedDelayString = "${observability.snapshots.interval}",
            initialDelayString = "${observability.snapshots.interval}")
    public synchronized void writeSnapshot() throws IOException {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("schemaVersion", 1);
        snapshot.put("service", "checkout");
        snapshot.put("runId", runId);
        snapshot.put("instanceId", instanceId);
        snapshot.put("sequence", ++sequence);
        snapshot.put("epochMs", System.currentTimeMillis());
        snapshot.put("uptimeMs", (System.nanoTime() - startedAtNanos) / 1_000_000);
        Map<String, Double> servlet = new LinkedHashMap<>();
        for (String metric : List.of("tomcat.threads.busy", "tomcat.threads.current", "tomcat.threads.config.max")) {
            Gauge gauge = meters.find(metric).gauge();
            double value = gauge == null ? Double.NaN : gauge.value();
            servlet.put(metric, Double.isFinite(value) ? value : null);
        }
        snapshot.put("servlet", servlet);
        snapshot.put("httpPool", poolSnapshot(connectionPool.getTotalStats()));
        Map<String, Object> httpRoutes = new LinkedHashMap<>();
        for (var route : connectionPool.getRoutes()) {
            httpRoutes.put(route.toString(), poolSnapshot(connectionPool.getStats(route)));
        }
        snapshot.put("httpRoutes", httpRoutes);
        Files.writeString(file, jsonWriter.writeValueAsString(snapshot) + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private Map<String, Integer> poolSnapshot(PoolStats stats) {
        return Map.of("leased", stats.getLeased(), "pending", stats.getPending(),
                "available", stats.getAvailable(), "max", stats.getMax());
    }
}
