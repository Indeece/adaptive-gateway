package ru.indeece.gateway.config;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Путь и интервал записи снимков ресурсов.
 */
public record SnapshotProperties(boolean enabled, Path file, Duration interval) {
}
