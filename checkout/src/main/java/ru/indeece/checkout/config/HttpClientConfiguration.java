package ru.indeece.checkout.config;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.util.Timeout;
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
    PoolingHttpClientConnectionManager upstreamConnectionPool(UpstreamProperties settings) {
        var connectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(Timeout.of(settings.connectTimeout()))
                .setSocketTimeout(Timeout.of(settings.socketTimeout()))
                .build();
        return PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(settings.maxConnections())
                .setMaxConnPerRoute(settings.maxConnectionsPerRoute())
                .setDefaultConnectionConfig(connectionConfig)
                .build();
    }

    private RestClient createClient(CloseableHttpClient httpClient, UpstreamProperties settings,
            UpstreamEndpointProperties endpoint) {
        var factory = new HttpComponentsClientHttpRequestFactory(httpClient);
        factory.setConnectionRequestTimeout(settings.connectionRequestTimeout());
        factory.setReadTimeout(endpoint.readTimeout());
        return RestClient.builder()
                .baseUrl(endpoint.baseUrl())
                .requestFactory(factory)
                .build();
    }

    @Bean
    RestClient inventoryClient(CloseableHttpClient httpClient, UpstreamProperties settings) {
        return createClient(httpClient, settings, settings.inventory());
    }

}
