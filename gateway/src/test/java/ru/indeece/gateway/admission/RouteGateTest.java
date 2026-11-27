package ru.indeece.gateway.admission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Проверяет допуск, освобождение места и смену лимита.
 */
class RouteGateTest {
    @Test
    void releasesOnlyOnePlaceAfterRepeatedClose() {
        RouteGate gate = new RouteGate(2);
        RoutePermit first = gate.tryAcquire().orElseThrow();
        RoutePermit second = gate.tryAcquire().orElseThrow();
        assertTrue(gate.tryAcquire().isEmpty());

        first.close();
        first.close();
        assertEquals(1, gate.active());

        RoutePermit next = gate.tryAcquire().orElseThrow();
        assertTrue(gate.tryAcquire().isEmpty());
        second.close();
        next.close();
        assertEquals(0, gate.active());
    }

    @Test
    void waitsForActiveToDropBelowLoweredLimitBeforeAdmittingMore() {
        RouteGate gate = new RouteGate(3);
        RoutePermit first = gate.tryAcquire().orElseThrow();
        RoutePermit second = gate.tryAcquire().orElseThrow();
        RoutePermit third = gate.tryAcquire().orElseThrow();

        gate.updateLimit(2);
        assertEquals(3, gate.active());
        assertTrue(gate.tryAcquire().isEmpty());

        third.close();
        assertEquals(2, gate.active());
        assertTrue(gate.tryAcquire().isEmpty());

        second.close();
        RoutePermit next = gate.tryAcquire().orElseThrow();
        assertEquals(2, gate.active());
        first.close();
        next.close();
        assertEquals(0, gate.active());
    }

    @Test
    void admitsAnotherRequestWhenLimitIncreases() {
        RouteGate gate = new RouteGate(1);
        try (RoutePermit first = gate.tryAcquire().orElseThrow()) {
            assertTrue(gate.tryAcquire().isEmpty());
            gate.updateLimit(2);
            try (RoutePermit second = gate.tryAcquire().orElseThrow()) {
                assertEquals(2, gate.active());
                assertTrue(gate.tryAcquire().isEmpty());
            }
            assertEquals(1, gate.active());
        }
        assertEquals(0, gate.active());
    }

    @Test
    void rejectsNonPositiveLimitWithoutReplacingCurrentLimit() {
        assertThrows(IllegalArgumentException.class, () -> new RouteGate(0));
        RouteGate gate = new RouteGate(1);
        assertThrows(IllegalArgumentException.class, () -> gate.updateLimit(-1));

        try (RoutePermit permit = gate.tryAcquire().orElseThrow()) {
            assertTrue(gate.tryAcquire().isEmpty());
        }
        assertEquals(0, gate.active());
    }
}
