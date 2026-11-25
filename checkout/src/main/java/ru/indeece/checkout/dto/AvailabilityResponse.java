package ru.indeece.checkout.dto;

/**
 * Наличие и остаток одного товара в ответе Inventory.
 */
public record AvailabilityResponse(String sku, boolean available, int quantity) {
}
