package ru.indeece.checkout;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import java.net.SocketTimeoutException;

@RestControllerAdvice
public class ApiErrorHandler {
    public record ErrorBody(String code, String message) {}

    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    ResponseEntity<ErrorBody> notFound(org.springframework.web.server.ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(new ErrorBody("not_found", "Unknown SKU"));
    }

    @ExceptionHandler(RestClientResponseException.class)
    ResponseEntity<?> upstreamStatus(RestClientResponseException error) {
        int status = error.getStatusCode().value();
        if (status == 404 || status == 502 || status == 503 || status == 504) {
            return ResponseEntity.status(status).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(error.getResponseBodyAsByteArray());
        }
        return ResponseEntity.status(502).body(new ErrorBody("upstream_transport", "Unexpected upstream response"));
    }

    @ExceptionHandler(RestClientException.class)
    ResponseEntity<ErrorBody> upstreamFailure(RestClientException error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException
                    || cause instanceof org.apache.hc.core5.http.ConnectionRequestTimeoutException) {
                return ResponseEntity.status(504).body(new ErrorBody("upstream_timeout", "Upstream timed out"));
            }
        }
        return ResponseEntity.status(502).body(new ErrorBody("upstream_transport", "Upstream unavailable"));
    }

}
