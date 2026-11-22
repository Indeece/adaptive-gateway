package ru.indeece.gateway.controller;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import ru.indeece.gateway.dto.AvailabilityResponse;
import ru.indeece.gateway.dto.QuoteResponse;

/**
 * Принимает внешние запросы наличия и цены и направляет их в Inventory или Checkout.
 */
@RestController
public class GatewayController {
    private final RestClient inventoryClient;
    private final RestClient checkoutClient;

    public GatewayController(@Qualifier("inventoryClient") RestClient inventoryClient,
                             @Qualifier("checkoutClient") RestClient checkoutClient) {
        this.inventoryClient = inventoryClient;
        this.checkoutClient = checkoutClient;
    }

    @GetMapping("/availability/{sku}")
    public AvailabilityResponse getAvailability(@PathVariable String sku) {
        AvailabilityResponse response = inventoryClient.get()
                .uri("/availability/{sku}", sku)
                .retrieve()
                .body(AvailabilityResponse.class);
        if (response == null || !sku.equals(response.sku())) {
            throw new RestClientException("Invalid availability response");
        }
        return response;
    }

    @GetMapping("/quotes/{sku}")
    public QuoteResponse getQuote(@PathVariable String sku) {
        QuoteResponse response = checkoutClient.get()
                .uri("/quotes/{sku}", sku)
                .retrieve()
                .body(QuoteResponse.class);
        if (response == null || !sku.equals(response.sku())) {
            throw new RestClientException("Invalid quote response");
        }
        return response;
    }
}
