package ru.indeece.gateway.admission;

import java.time.Duration;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import ru.indeece.gateway.config.AdmissionMode;
import ru.indeece.gateway.config.QuoteAdmissionProperties;
import ru.indeece.gateway.dto.QuoteResponse;
import ru.indeece.gateway.error.QuoteAdmissionException;

/**
 * Применяет выбранный режим допуска перед вызовом Checkout на маршруте quotes.
 */
@Component
public class QuoteAdmission {
    private final AdmissionMode mode;
    private final RouteGate gate;
    private final Duration queueWait;

    public QuoteAdmission(QuoteAdmissionProperties properties) {
        mode = properties.mode();
        gate = new RouteGate(properties.quotesLimit(), properties.queueCapacity());
        queueWait = properties.queueWait();
    }

    public QuoteResponse execute(Supplier<QuoteResponse> checkoutCall) {
        if (mode == AdmissionMode.B0) {
            return checkoutCall.get();
        }

        try (RoutePermit permit = acquirePermit()) {
            return checkoutCall.get();
        }
    }

    public int active() {
        return gate.active();
    }

    public int queued() {
        return gate.queued();
    }

    private RoutePermit acquirePermit() {
        try {
            return gate.acquire(queueWait).orElseThrow(QuoteAdmissionException::overloaded);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw QuoteAdmissionException.interrupted(exception);
        }
    }
}
