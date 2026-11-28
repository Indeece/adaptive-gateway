package ru.indeece.gateway.config;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Хранит режим допуска, лимит одновременных quotes и настройки очереди Gateway.
 */
@ConfigurationProperties("admission")
public record QuoteAdmissionProperties(AdmissionMode mode, int quotesLimit, int queueCapacity, Duration queueWait) {
    public QuoteAdmissionProperties {
        Objects.requireNonNull(mode, "Режим допуска должен быть задан");
        Objects.requireNonNull(queueWait, "Время ожидания в очереди должно быть задано");
        if (queueCapacity < 1 || queueWait.isNegative() || queueWait.isZero()) {
            throw new IllegalArgumentException("Размер очереди и время ожидания должны быть положительными");
        }
    }
}
