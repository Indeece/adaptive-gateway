package ru.indeece.gateway.dto;

import java.math.BigDecimal;

/**
 * Доступность товара и фиксированная оценка его стоимости.
 */
public record QuoteResponse(String sku, boolean available, BigDecimal price, String currency) {
}
