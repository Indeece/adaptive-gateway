package ru.indeece.inventory.error;

import org.springframework.http.HttpStatus;

/**
 * Передаёт HTTP-статус и код отказа или истечения срока задачи Inventory.
 */
public class InventoryTaskException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public InventoryTaskException(HttpStatus status, String code, String message) {
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
