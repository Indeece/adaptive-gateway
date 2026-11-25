package ru.indeece.inventory.dto;

/**
 * Фактическая занятость и ёмкость одного рабочего пула Inventory.
 */
public record ExecutorResourceSnapshot(int busyWorkers, int queuedTasks, int workerLimit, int queueCapacity) {
}
