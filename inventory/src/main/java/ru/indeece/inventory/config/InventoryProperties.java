package ru.indeece.inventory.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Значения настроек Inventory, загруженные Spring из раздела inventory.
 */
@ConfigurationProperties("inventory")
public record InventoryProperties(boolean splitPools, int sharedWorkers, int sharedQueueCapacity,
        int batchWorkers, int batchQueueCapacity, int availabilityWorkers, int availabilityQueueCapacity,
        Duration deadline, Duration availabilityDuration, Duration batchDuration,
        Duration slowedBatchDuration) implements InventorySettings {
}
