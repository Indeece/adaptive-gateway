package ru.indeece.inventory.config;

import java.time.Duration;

/**
 * Контракт настроек рабочих пулов и времени выполнения операций Inventory.
 */
public interface InventorySettings {
    boolean splitPools();

    int sharedWorkers();

    int sharedQueueCapacity();

    int batchWorkers();

    int batchQueueCapacity();

    int availabilityWorkers();

    int availabilityQueueCapacity();

    Duration deadline();

    Duration availabilityDuration();

    Duration batchDuration();

    Duration slowedBatchDuration();
}
