package ru.indeece.gateway.monitoring;

import java.util.HashMap;
import java.util.Map;
import ru.indeece.gateway.dto.RouteMetricsSnapshot;

/**
 * Атомарно обновляет счётчики одного маршрута и возвращает согласованный снимок.
 */
final class RouteCounters {
    private long started;
    private long completed;
    private long totalDurationNanos;
    private long maxDurationNanos;
    private final Map<String, Long> outcomes = new HashMap<>();

    synchronized void started() {
        started++;
    }

    synchronized void completed(String outcome, long durationNanos) {
        completed++;
        outcomes.merge(outcome, 1L, Long::sum);
        totalDurationNanos += durationNanos;
        maxDurationNanos = Math.max(maxDurationNanos, durationNanos);
    }

    synchronized RouteMetricsSnapshot snapshot() {
        return new RouteMetricsSnapshot(started, completed, started - completed,
                Map.copyOf(outcomes), totalDurationNanos / 1_000_000.0,
                maxDurationNanos / 1_000_000.0);
    }
}
