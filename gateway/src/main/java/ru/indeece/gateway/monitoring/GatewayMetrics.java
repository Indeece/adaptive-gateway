package ru.indeece.gateway.monitoring;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import ru.indeece.gateway.dto.RouteMetricsSnapshot;

/**
 * Учитывает вызовы обоих маршрутов, сохраняя выполнение HTTP-запроса вне блокировок счётчиков.
 */
@Component
public class GatewayMetrics {
    private final Map<GatewayRoute, RouteCounters> routes = new EnumMap<>(GatewayRoute.class);

    public GatewayMetrics() {
        for (GatewayRoute route : GatewayRoute.values()) {
            routes.put(route, new RouteCounters());
        }
    }

    public <T> T measure(GatewayRoute route, Supplier<T> call) {
        RouteCounters counters = routes.get(route);
        counters.started();
        long startedAt = System.nanoTime();
        String outcome = "unexpected_error";
        try {
            T result = call.get();
            outcome = "success";
            return result;
        } catch (RestClientResponseException exception) {
            outcome = "http_" + exception.getStatusCode().value();
            throw exception;
        } catch (RestClientException exception) {
            outcome = "transport_error";
            throw exception;
        } finally {
            counters.completed(outcome, System.nanoTime() - startedAt);
        }
    }

    public Map<GatewayRoute, RouteMetricsSnapshot> snapshot() {
        Map<GatewayRoute, RouteMetricsSnapshot> snapshots = new EnumMap<>(GatewayRoute.class);
        routes.forEach((route, counters) -> snapshots.put(route, counters.snapshot()));
        return Map.copyOf(snapshots);
    }
}
