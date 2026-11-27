package ru.indeece.gateway;

import static org.junit.jupiter.api.Assertions.assertSame;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import ru.indeece.gateway.admission.QuoteAdmission;
import ru.indeece.gateway.dto.QuoteResponse;

/**
 * Проверяет запуск Gateway и пропуск запросов в B0 с настроенным лимитом.
 */
@SpringBootTest(properties = {"admission.mode=B0", "admission.quotes-limit=1"})
class GatewayApplicationTests {
    @Autowired
    private QuoteAdmission quoteAdmission;

    @Test
    void b0DoesNotApplyLimitToOverlappingCalls() {
        QuoteResponse upstream = new QuoteResponse("SKU-001", true, BigDecimal.valueOf(100), "RUB");
        QuoteResponse result = quoteAdmission.execute(() -> quoteAdmission.execute(() -> upstream));

        assertSame(upstream, result);
    }

}
