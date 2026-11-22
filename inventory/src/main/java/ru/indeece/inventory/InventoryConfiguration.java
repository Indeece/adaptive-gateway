package ru.indeece.inventory;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class InventoryConfiguration {
    @Bean(destroyMethod = "shutdownNow")
    ThreadPoolExecutor inventoryExecutor() {
        return new ThreadPoolExecutor(
                8, 8, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(16),
                Thread.ofPlatform().name("inventory-worker-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }
}
