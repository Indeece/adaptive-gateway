package ru.indeece.inventory.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.indeece.inventory.dto.AvailabilityResponse;
import ru.indeece.inventory.dto.BatchAvailabilityResponse;
import ru.indeece.inventory.service.InventoryService;

/**
 * Предоставляет отдельную и пакетную HTTP-проверку наличия товаров.
 */
@RestController
public class InventoryController {
    private final InventoryService inventoryService;

    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @GetMapping("/availability/{sku}")
    public AvailabilityResponse getAvailability(@PathVariable String sku) {
        return inventoryService.getAvailability(sku);
    }

    @GetMapping("/internal/batch-availability")
    public BatchAvailabilityResponse getBatchAvailability(@RequestParam String sku) {
        return inventoryService.getBatchAvailability(sku);
    }
}
