package ru.indeece.gateway.admission;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Проверяет допуск, освобождение места и смену лимита.
 */
class RouteGateTest {
    @Test
    void admitsQueuedRequestsInOrderAndRefusesWhenQueueIsFull() throws Exception {
        RouteGate gate = new RouteGate(1, 2);

        try (var executor = Executors.newFixedThreadPool(3);
                RoutePermit inProgress = gate.tryAcquire().orElseThrow()) {
            var firstWaiting = executor.submit(() -> gate.acquire(Duration.ofSeconds(10)).orElseThrow());
            await().atMost(Duration.ofSeconds(2)).until(() -> gate.queued() == 1);
            var secondWaiting = executor.submit(() -> gate.acquire(Duration.ofSeconds(10)).orElseThrow());
            await().atMost(Duration.ofSeconds(2)).until(() -> gate.queued() == 2);
            var excess = executor.submit(() -> gate.acquire(Duration.ofSeconds(10)));

            assertTrue(excess.get(2, TimeUnit.SECONDS).isEmpty());
            assertEquals(1, gate.active());
            assertEquals(2, gate.queued());

            inProgress.close();
            try (RoutePermit first = firstWaiting.get(2, TimeUnit.SECONDS)) {
                assertFalse(secondWaiting.isDone());
                assertEquals(1, gate.active());
                assertEquals(1, gate.queued());
            }
            try (RoutePermit second = secondWaiting.get(2, TimeUnit.SECONDS)) {
                assertEquals(1, gate.active());
                assertEquals(0, gate.queued());
            }
        }
        assertEquals(0, gate.active());
    }

    @Test
    void expiredWaitLeavesQueueAndDoesNotTakeSlot() throws Exception {
        RouteGate gate = new RouteGate(1, 1);
        Duration wait = Duration.ofMillis(50);

        try (RoutePermit inProgress = gate.tryAcquire().orElseThrow()) {
            long started = System.nanoTime();
            assertTrue(gate.acquire(wait).isEmpty());
            assertTrue(System.nanoTime() - started >= wait.toNanos());
            assertEquals(1, gate.active());
            assertEquals(0, gate.queued());
        }
        assertEquals(0, gate.active());
    }

    @Test
    void interruptedWaitFreesQueuePosition() throws Exception {
        RouteGate gate = new RouteGate(1, 1);
        try (var executor = Executors.newSingleThreadExecutor();
                RoutePermit inProgress = gate.tryAcquire().orElseThrow()) {
            var waiting = executor.submit(() -> gate.acquire(Duration.ofSeconds(10)));
            await().atMost(Duration.ofSeconds(2)).until(() -> gate.queued() == 1);

            assertTrue(waiting.cancel(true));
            await().atMost(Duration.ofSeconds(2)).until(() -> gate.queued() == 0);
            assertEquals(1, gate.active());
        }
        assertEquals(0, gate.active());
    }

    @Test
    void simultaneousAcquisitionsDoNotExceedLimit() throws Exception {
        int limit = 3;
        int attempts = 8;
        RouteGate gate = new RouteGate(limit);
        CyclicBarrier start = new CyclicBarrier(attempts);
        List<RoutePermit> permits = new ArrayList<>();

        try (var executor = Executors.newFixedThreadPool(attempts)) {
            List<Future<Optional<RoutePermit>>> acquisitions = new ArrayList<>();
            for (int attempt = 0; attempt < attempts; attempt++) {
                acquisitions.add(executor.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    return gate.tryAcquire();
                }));
            }
            for (var acquisition : acquisitions) {
                acquisition.get(5, TimeUnit.SECONDS).ifPresent(permits::add);
            }
        }

        assertEquals(limit, permits.size());
        assertEquals(limit, gate.active());
        permits.forEach(RoutePermit::close);
        assertEquals(0, gate.active());
    }

    @Test
    void simultaneousCloseReleasesTheSamePermitOnce() throws Exception {
        RouteGate gate = new RouteGate(2);
        RoutePermit shared = gate.tryAcquire().orElseThrow();
        RoutePermit other = gate.tryAcquire().orElseThrow();
        CyclicBarrier start = new CyclicBarrier(2);
        Callable<Void> closeShared = () -> {
            start.await(5, TimeUnit.SECONDS);
            shared.close();
            return null;
        };

        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstClose = executor.submit(closeShared);
            var secondClose = executor.submit(closeShared);
            firstClose.get(5, TimeUnit.SECONDS);
            secondClose.get(5, TimeUnit.SECONDS);
        }

        assertEquals(1, gate.active());
        other.close();
        shared.close();
        assertEquals(0, gate.active());
    }

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
    void raisingLimitWakesQueuedRequestWhileFirstPermitIsStillInUse() throws Exception {
        RouteGate gate = new RouteGate(1, 1);
        try (var executor = Executors.newSingleThreadExecutor();
                RoutePermit first = gate.tryAcquire().orElseThrow()) {
            assertTrue(gate.tryAcquire().isEmpty());
            var waiting = executor.submit(() -> gate.acquire(Duration.ofSeconds(10)).orElseThrow());
            await().atMost(Duration.ofSeconds(2)).until(() -> gate.queued() == 1);
            gate.updateLimit(2);
            try (RoutePermit second = waiting.get(2, TimeUnit.SECONDS)) {
                assertEquals(2, gate.active());
                assertEquals(0, gate.queued());
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
