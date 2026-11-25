package ru.indeece.inventory.controller;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.indeece.inventory.dto.BatchFaultState;
import ru.indeece.inventory.execution.InventoryTaskExecutor;

/**
 * Переключает профиль замедления новых batch-задач в локальном стенде.
 */
@RestController
@ConditionalOnProperty(name = "inventory.fault-control.enabled", havingValue = "true")
public class BatchFaultController {
    private final InventoryTaskExecutor taskExecutor;

    public BatchFaultController(InventoryTaskExecutor taskExecutor) {
        this.taskExecutor = taskExecutor;
    }

    @PostMapping("/internal/fault")
    public BatchFaultState setFault(@RequestParam boolean enabled) {
        return taskExecutor.setFault(enabled);
    }
}
