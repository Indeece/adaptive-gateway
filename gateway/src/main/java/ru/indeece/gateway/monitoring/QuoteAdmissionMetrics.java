package ru.indeece.gateway.monitoring;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;
import ru.indeece.gateway.admission.QuoteAdmission;

/**
 * Публикует количество занятых слотов и длину очереди quotes в Gateway.
 */
@Component
public class QuoteAdmissionMetrics implements MeterBinder {
    private final QuoteAdmission admission;

    public QuoteAdmissionMetrics(QuoteAdmission admission) {
        this.admission = admission;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        registry.gauge("gateway.quotes.admitted", admission, QuoteAdmission::active);
        registry.gauge("gateway.quotes.queued", admission, QuoteAdmission::queued);
    }
}
