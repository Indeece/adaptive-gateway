package ru.indeece.checkout.service;

import java.math.BigDecimal;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import ru.indeece.checkout.dto.AvailabilityResponse;
import ru.indeece.checkout.dto.BatchAvailabilityResponse;
import ru.indeece.checkout.dto.QuoteResponse;

/**
 * Проверяет наличие одним пакетным вызовом Inventory и формирует фиксированную оценку цены.
 */
@Service
public class QuoteService {
    private static final BigDecimal PRICE = new BigDecimal("100.00");
    private final RestClient inventoryClient;

    public QuoteService(@Qualifier("inventoryClient") RestClient inventoryClient) {
        this.inventoryClient = inventoryClient;
    }

    public QuoteResponse getQuote(String sku) {
        BatchAvailabilityResponse response = inventoryClient.get()
                .uri("/internal/batch-availability?sku={sku}", sku)
                .retrieve()
                .body(BatchAvailabilityResponse.class);
        if (response == null || response.items() == null || response.items().size() != 1) {
            throw new RestClientException("Invalid batch response");
        }

        AvailabilityResponse availability = response.items().getFirst();
        if (availability == null || !sku.equals(availability.sku())) {
            throw new RestClientException("Invalid batch response");
        }
        return new QuoteResponse(sku, availability.available(), PRICE, "RUB");
    }
}
