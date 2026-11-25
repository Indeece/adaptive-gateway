package ru.indeece.inventory.service;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import ru.indeece.inventory.dto.AvailabilityResponse;
import ru.indeece.inventory.dto.BatchAvailabilityResponse;
import ru.indeece.inventory.execution.InventoryOperation;
import ru.indeece.inventory.execution.InventoryTaskExecutor;

/**
 * Получает фиксированные остатки и выполняет обе операции через общий ресурс Inventory.
 */
@Service
public class InventoryService {
    private final InventoryTaskExecutor taskExecutor;

    public InventoryService(InventoryTaskExecutor taskExecutor) {
        this.taskExecutor = taskExecutor;
    }

    public AvailabilityResponse getAvailability(String sku) {
        AvailabilityResponse availability = findAvailability(sku);
        return taskExecutor.execute(InventoryOperation.AVAILABILITY, () -> availability);
    }

    public BatchAvailabilityResponse getBatchAvailability(String sku) {
        AvailabilityResponse availability = findAvailability(sku);
        return taskExecutor.execute(InventoryOperation.BATCH,
                () -> new BatchAvailabilityResponse(List.of(availability)));
    }

    private AvailabilityResponse findAvailability(String sku) {
        if (!"SKU-001".equals(sku)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return new AvailabilityResponse(sku, true, 10);
    }
}
