package ru.indeece.inventory.monitoring;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;
import ru.indeece.inventory.execution.InventoryTaskExecutor;

/**
 * Публикует состояние рабочих пулов и замедления для графиков Grafana.
 */
@Component
public class InventoryMetrics implements MeterBinder {
    private final InventoryTaskExecutor tasks;

    public InventoryMetrics(InventoryTaskExecutor tasks) {
        this.tasks = tasks;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        registry.gauge("inventory.tasks.active", tasks, InventoryTaskExecutor::activeTasks);
        registry.gauge("inventory.tasks.queued", tasks, InventoryTaskExecutor::queuedTasks);
        registry.gauge("inventory.fault.enabled", tasks, executor -> executor.faultEnabled() ? 1 : 0);
    }
}
