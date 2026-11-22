package ru.indeece.inventory;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiErrorHandler {
    public record ErrorBody(String code, String message) {}

    @ExceptionHandler(InventoryWorkException.class)
    ResponseEntity<ErrorBody> workFailure(InventoryWorkException error) {
        return ResponseEntity.status(error.status()).body(new ErrorBody(error.code(), error.getMessage()));
    }

    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    ResponseEntity<ErrorBody> notFound(org.springframework.web.server.ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(new ErrorBody("not_found", "Unknown SKU"));
    }

}
