package ru.indeece.checkout.dto;

import java.util.List;

/**
 * Результаты проверки наличия товаров в одном пакетном ответе.
 */
public record BatchAvailabilityResponse(List<AvailabilityResponse> items) {
}
