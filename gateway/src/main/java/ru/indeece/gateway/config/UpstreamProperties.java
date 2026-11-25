package ru.indeece.gateway.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Настройки пула соединений, таймаутов и адресов вызываемых сервисов.
 */
@ConfigurationProperties("upstream")
public record UpstreamProperties(Duration connectTimeout, Duration connectionRequestTimeout,
        Duration socketTimeout, int maxConnections, int maxConnectionsPerRoute,
        UpstreamEndpointProperties inventory, UpstreamEndpointProperties checkout) {
}
