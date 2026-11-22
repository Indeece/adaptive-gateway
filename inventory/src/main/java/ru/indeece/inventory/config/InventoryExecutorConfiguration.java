package ru.indeece.inventory.config;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Создаёт общий пул из восьми воркеров с очередью на шестнадцать задач.
 */
@Configuration
public class InventoryExecutorConfiguration {
    @Bean(destroyMethod = "shutdownNow")
    ThreadPoolExecutor inventoryExecutor() {
        return new ThreadPoolExecutor(
                8, 8, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(16),
                Thread.ofPlatform().name("inventory-worker-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }
}
