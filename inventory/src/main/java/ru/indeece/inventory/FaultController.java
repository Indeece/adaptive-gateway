package ru.indeece.inventory;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "inventory.fault-control.enabled", havingValue = "true")
public class FaultController {
    private final InventoryWorkPool workPool;

    public FaultController(InventoryWorkPool workPool) {
        this.workPool = workPool;
    }

    @PostMapping("/internal/fault")
    public InventoryWorkPool.FaultState setFault(@RequestParam boolean enabled) {
        return workPool.setFault(enabled);
    }
}
