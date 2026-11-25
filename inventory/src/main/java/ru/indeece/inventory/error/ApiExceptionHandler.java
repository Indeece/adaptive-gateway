package ru.indeece.inventory.error;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import ru.indeece.inventory.dto.ErrorResponse;

/**
 * Преобразует ошибки поиска товара, перегрузки и дедлайна в HTTP-ответы Inventory.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(InventoryTaskException.class)
    ResponseEntity<ErrorResponse> handleTaskFailure(InventoryTaskException error) {
        return ResponseEntity.status(error.status())
                .body(new ErrorResponse(error.code(), error.getMessage()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ErrorResponse> handleUnknownSku(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode())
                .body(new ErrorResponse("not_found", "Unknown SKU"));
    }

}
