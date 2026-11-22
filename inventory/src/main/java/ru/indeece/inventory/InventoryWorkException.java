package ru.indeece.inventory;

import org.springframework.http.HttpStatus;

public class InventoryWorkException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public InventoryWorkException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
