package ru.indeece.checkout.config;

import java.time.Duration;

/**
 * Адрес сервиса и таймаут чтения его ответа.
 */
public record UpstreamEndpointProperties(String baseUrl, Duration readTimeout) {
}
