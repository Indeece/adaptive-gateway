package ru.indeece.checkout.error;

import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.springframework.web.server.ResponseStatusException;
import java.net.SocketTimeoutException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import ru.indeece.checkout.dto.ErrorResponse;

/**
 * Преобразует ошибки приложения и исходящих вызовов в согласованные HTTP-ответы.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ErrorResponse> handleUnknownSku(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode())
                .body(new ErrorResponse("not_found", "Unknown SKU"));
    }

    @ExceptionHandler(RestClientResponseException.class)
    ResponseEntity<?> handleUpstreamResponse(RestClientResponseException error) {
        int status = error.getStatusCode().value();
        if (status == 404 || status == 502 || status == 503 || status == 504) {
            return ResponseEntity.status(status)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(error.getResponseBodyAsByteArray());
        }
        return ResponseEntity.status(502).body(new ErrorResponse("upstream_transport", "Unexpected upstream response"));
    }

    @ExceptionHandler(RestClientException.class)
    ResponseEntity<ErrorResponse> handleUpstreamFailure(RestClientException error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException
                    || cause instanceof ConnectionRequestTimeoutException) {
                return ResponseEntity.status(504).body(new ErrorResponse("upstream_timeout", "Upstream timed out"));
            }
        }
        return ResponseEntity.status(502).body(new ErrorResponse("upstream_transport", "Upstream unavailable"));
    }

}
