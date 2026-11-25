package ru.indeece.gateway.dto;

import java.util.Map;

/**
 * Накопленные исходы и длительности вызовов одного маршрута до чтения и проверки тела ответа.
 */
public record RouteMetricsSnapshot(long started, long completed, long active,
                                   Map<String, Long> outcomes,
                                   double totalDurationMs, double maxDurationMs) {
}
