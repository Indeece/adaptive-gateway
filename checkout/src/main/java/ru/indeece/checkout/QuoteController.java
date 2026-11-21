package ru.indeece.checkout;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.beans.factory.annotation.Qualifier;

@RestController
public class QuoteController {
    private final RestClient inventory;
    public QuoteController(@Qualifier("inventoryClient") RestClient inventory) { this.inventory = inventory; }
    public record Availability(String sku, boolean available, int quantity) {}
    public record BatchAvailability(List<Availability> items) {}
    public record Quote(String sku, boolean available, BigDecimal price, String currency) {}

    @GetMapping("/quotes/{sku}")
    public Quote quote(@PathVariable String sku) {
        var batch = inventory.get().uri("/internal/batch-availability?sku={sku}", sku)
                .retrieve().body(BatchAvailability.class);
        if (batch == null || batch.items() == null || batch.items().size() != 1
                || !sku.equals(batch.items().getFirst().sku())) {
            throw new org.springframework.web.client.RestClientException("Invalid batch response");
        }
        return new Quote(sku, batch.items().getFirst().available(), new BigDecimal("100.00"), "RUB");
    }
}
