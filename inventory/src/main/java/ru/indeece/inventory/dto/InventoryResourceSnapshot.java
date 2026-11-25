package ru.indeece.inventory.dto;

/**
 * Состояние общего ресурса и накопленные исходы ожидания задач с момента запуска процесса.
 */
public record InventoryResourceSnapshot(
        int activeTasks, int queuedTasks, int workerLimit, int queueCapacity,
        long accepted, long rejected, long deadlineExceeded, long executorCompleted,
        BatchFaultState fault) {
}
