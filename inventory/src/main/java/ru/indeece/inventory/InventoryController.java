package ru.indeece.inventory;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class InventoryController {
    public record Availability(String sku, boolean available, int quantity) {}
    public record BatchAvailability(List<Availability> items) {}

    @GetMapping("/availability/{sku}")
    public Availability availability(@PathVariable String sku) {
        if (!"SKU-001".equals(sku)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return new Availability(sku, true, 10);
    }

    @GetMapping("/internal/batch-availability")
    public BatchAvailability batch(@RequestParam String sku) {
        return new BatchAvailability(List.of(availability(sku)));
    }
}
