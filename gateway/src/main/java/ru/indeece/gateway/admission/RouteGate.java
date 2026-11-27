package ru.indeece.gateway.admission;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Ограничивает число запросов в работе и допускает ожидающие запросы в порядке очереди.
 */
public final class RouteGate {
    private final int queueCapacity;
    private final Deque<Object> waiting = new ArrayDeque<>();
    private int limit;
    private int active;

    public RouteGate(int limit) {
        this(limit, 0);
    }

    public RouteGate(int limit, int queueCapacity) {
        if (queueCapacity < 0) {
            throw new IllegalArgumentException("Размер очереди не может быть отрицательным");
        }
        this.limit = requirePositiveLimit(limit);
        this.queueCapacity = queueCapacity;
    }

    public synchronized Optional<RoutePermit> tryAcquire() {
        if (active >= limit || !waiting.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(issuePermit());
    }

    public synchronized Optional<RoutePermit> acquire(Duration maxWait) throws InterruptedException {
        long waitNanos = maxWait.toNanos();
        if (waitNanos < 0) {
            throw new IllegalArgumentException("Время ожидания не может быть отрицательным");
        }
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }

        Optional<RoutePermit> immediate = tryAcquire();
        if (immediate.isPresent() || waiting.size() >= queueCapacity || waitNanos == 0) {
            return immediate;
        }

        Object position = new Object();
        long deadline = System.nanoTime() + waitNanos;
        waiting.addLast(position);
        try {
            long remaining;
            while ((remaining = deadline - System.nanoTime()) > 0) {
                if (Thread.interrupted()) {
                    throw new InterruptedException();
                }
                if (waiting.peekFirst() == position && active < limit) {
                    return Optional.of(issuePermit());
                }
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
            }
            return Optional.empty();
        } finally {
            waiting.remove(position);
            notifyAll();
        }
    }

    public synchronized void updateLimit(int limit) {
        this.limit = requirePositiveLimit(limit);
        notifyAll();
    }

    public synchronized int active() {
        return active;
    }

    public synchronized int queued() {
        return waiting.size();
    }

    synchronized void release(RoutePermit permit) {
        if (permit.markClosed()) {
            active--;
            notifyAll();
        }
    }

    private RoutePermit issuePermit() {
        RoutePermit permit = new RoutePermit(this);
        active++;
        return permit;
    }

    private static int requirePositiveLimit(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("Лимит допуска должен быть положительным");
        }
        return limit;
    }
}
