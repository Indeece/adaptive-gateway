package ru.indeece.gateway.admission;

import java.util.Optional;

/**
 * Ограничивает число одновременно допущенных запросов на маршруте.
 */
public final class RouteGate {
    private int limit;
    private int active;

    public RouteGate(int limit) {
        this.limit = requirePositiveLimit(limit);
    }

    public synchronized Optional<RoutePermit> tryAcquire() {
        if (active >= limit) {
            return Optional.empty();
        }

        RoutePermit permit = new RoutePermit(this);
        active++;
        return Optional.of(permit);
    }

    public synchronized void updateLimit(int limit) {
        this.limit = requirePositiveLimit(limit);
    }

    public synchronized int active() {
        return active;
    }

    synchronized void release(RoutePermit permit) {
        if (permit.markClosed()) {
            active--;
        }
    }

    private static int requirePositiveLimit(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("Лимит допуска должен быть положительным");
        }
        return limit;
    }
}
