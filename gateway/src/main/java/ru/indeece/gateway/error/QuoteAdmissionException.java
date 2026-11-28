package ru.indeece.gateway.error;

/**
 * Сохраняет причину, по которой шлюз завершил запрос до вызова Checkout.
 */
public final class QuoteAdmissionException extends RuntimeException {
    private final String code;

    private QuoteAdmissionException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public static QuoteAdmissionException overloaded() {
        return new QuoteAdmissionException("gateway_overload", "Quote admission is temporarily unavailable", null);
    }

    public static QuoteAdmissionException interrupted(InterruptedException cause) {
        return new QuoteAdmissionException("gateway_interrupted", "Quote admission was interrupted", cause);
    }

    public String code() {
        return code;
    }
}
