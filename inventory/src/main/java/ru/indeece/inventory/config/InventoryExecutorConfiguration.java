package ru.indeece.inventory.config;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * Создаёт рабочие пулы Inventory из настроек выбранной топологии.
 */
@Configuration
public class InventoryExecutorConfiguration {
    @Bean(destroyMethod = "shutdownNow")
    @Primary
    ThreadPoolExecutor inventoryExecutor(InventorySettings settings) {
        int workers = settings.splitPools() ? settings.batchWorkers() : settings.sharedWorkers();
        int queueCapacity = settings.splitPools() ? settings.batchQueueCapacity() : settings.sharedQueueCapacity();
        return new ThreadPoolExecutor(
                workers, workers, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                Thread.ofPlatform().name("inventory-worker-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean(destroyMethod = "shutdownNow")
    @ConditionalOnProperty(name = "inventory.split-pools", havingValue = "true")
    ThreadPoolExecutor availabilityExecutor(InventorySettings settings) {
        return new ThreadPoolExecutor(settings.availabilityWorkers(), settings.availabilityWorkers(), 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(settings.availabilityQueueCapacity()),
                Thread.ofPlatform().name("inventory-availability-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }
}
