package ru.indeece.gateway.dto;

/**
 * Код и описание ошибки HTTP-запроса.
 */
public record ErrorResponse(String code, String message) {
}
