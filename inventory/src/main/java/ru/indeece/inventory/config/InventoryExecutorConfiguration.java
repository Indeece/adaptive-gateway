package ru.indeece.inventory.config;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * Создаёт общий пул из восьми воркеров с очередью на шестнадцать задач.
 */
@Configuration
public class InventoryExecutorConfiguration {
    @Bean(destroyMethod = "shutdownNow")
    @Primary
    ThreadPoolExecutor inventoryExecutor(@Value("${inventory.split-pools:false}") boolean splitPools) {
        int workers = splitPools ? 6 : 8;
        int queueCapacity = splitPools ? 12 : 16;
        return new ThreadPoolExecutor(
                workers, workers, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                Thread.ofPlatform().name("inventory-worker-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean(destroyMethod = "shutdownNow")
    @ConditionalOnProperty(name = "inventory.split-pools", havingValue = "true")
    ThreadPoolExecutor availabilityExecutor() {
        return new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(4),
                Thread.ofPlatform().name("inventory-availability-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }
}
