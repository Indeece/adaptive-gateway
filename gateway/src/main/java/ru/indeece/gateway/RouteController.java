package ru.indeece.gateway;

import java.math.BigDecimal;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

@RestController
public class RouteController {
    private final RestClient inventory;
    private final RestClient checkout;
    public RouteController(@Qualifier("inventoryClient") RestClient inventory,
                           @Qualifier("checkoutClient") RestClient checkout) {
        this.inventory = inventory;
        this.checkout = checkout;
    }
    public record Availability(String sku, boolean available, int quantity) {}
    public record Quote(String sku, boolean available, BigDecimal price, String currency) {}

    @GetMapping("/availability/{sku}")
    public Availability availability(@PathVariable String sku) {
        var result = inventory.get().uri("/availability/{sku}", sku).retrieve().body(Availability.class);
        if (result == null || !sku.equals(result.sku())) {
            throw new org.springframework.web.client.RestClientException("Invalid availability response");
        }
        return result;
    }

    @GetMapping("/quotes/{sku}")
    public Quote quote(@PathVariable String sku) {
        var result = checkout.get().uri("/quotes/{sku}", sku).retrieve().body(Quote.class);
        if (result == null || !sku.equals(result.sku())) {
            throw new org.springframework.web.client.RestClientException("Invalid quote response");
        }
        return result;
    }
}
