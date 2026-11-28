package ru.indeece.gateway.admission;

/**
 * Удерживает место одного допущенного запроса до освобождения через close.
 */
public final class RoutePermit implements AutoCloseable {
    private final RouteGate gate;
    private boolean closed;

    RoutePermit(RouteGate gate) {
        this.gate = gate;
    }

    @Override
    public void close() {
        gate.release(this);
    }

    // Вызывается под блокировкой RouteGate вместе с обновлением счётчика active.
    boolean markClosed() {
        if (closed) {
            return false;
        }
        closed = true;
        return true;
    }
}
