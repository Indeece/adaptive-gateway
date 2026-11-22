package ru.indeece.inventory;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class InventoryController {
    private final InventoryWorkPool workPool;

    public InventoryController(InventoryWorkPool workPool) {
        this.workPool = workPool;
    }

    public record Availability(String sku, boolean available, int quantity) {}
    public record BatchAvailability(List<Availability> items) {}

    @GetMapping("/availability/{sku}")
    public Availability availability(@PathVariable String sku) {
        Availability item = lookup(sku);
        return workPool.execute(() -> {
            Thread.sleep(15);
            return item;
        });
    }

    @GetMapping("/internal/batch-availability")
    public BatchAvailability batch(@RequestParam String sku) {
        Availability item = lookup(sku);
        return workPool.execute(() -> {
            Thread.sleep(45);
            return new BatchAvailability(List.of(item));
        });
    }

    private Availability lookup(String sku) {
        if (!"SKU-001".equals(sku)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return new Availability(sku, true, 10);
    }
}
