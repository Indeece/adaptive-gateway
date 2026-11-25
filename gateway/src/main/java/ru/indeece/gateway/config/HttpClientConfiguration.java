package ru.indeece.gateway.config;

import java.time.Duration;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Создаёт HTTP-клиенты с ограниченными пулами соединений и таймаутами.
 */
@Configuration
public class HttpClientConfiguration {
    @Bean(destroyMethod = "close")
    CloseableHttpClient upstreamHttpClient(PoolingHttpClientConnectionManager connectionPool) {
        return HttpClients.custom()
                .setConnectionManager(connectionPool)
                .setConnectionManagerShared(true)
                .disableAutomaticRetries()
                .disableRedirectHandling()
                .build();
    }

    @Bean(destroyMethod = "close")
    PoolingHttpClientConnectionManager upstreamConnectionPool() {
        var connectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(100))
                .setSocketTimeout(Timeout.ofMilliseconds(650))
                .build();
        return PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(100)
                .setMaxConnPerRoute(100)
                .setDefaultConnectionConfig(connectionConfig)
                .build();
    }

    private RestClient createClient(CloseableHttpClient httpClient, String baseUrl, int readTimeoutMs) {
        var factory = new HttpComponentsClientHttpRequestFactory(httpClient);
        factory.setConnectionRequestTimeout(Duration.ofMillis(100));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    @Bean
    RestClient inventoryClient(CloseableHttpClient httpClient,
            @Value("${upstream.inventory.base-url}") String baseUrl) {
        return createClient(httpClient, baseUrl, 650);
    }

    @Bean
    RestClient checkoutClient(CloseableHttpClient httpClient,
            @Value("${upstream.checkout.base-url}") String baseUrl) {
        return createClient(httpClient, baseUrl, 850);
    }

}
