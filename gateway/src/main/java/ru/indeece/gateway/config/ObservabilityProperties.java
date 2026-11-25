package ru.indeece.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Настройки записи снимков ресурсов и идентификатор эксперимента.
 */
@ConfigurationProperties("observability")
public record ObservabilityProperties(String runId, SnapshotProperties snapshots) {
}
