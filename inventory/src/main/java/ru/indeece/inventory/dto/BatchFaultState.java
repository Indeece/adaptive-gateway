package ru.indeece.inventory.dto;

/**
 * Применённый профиль замедления batch с серверным временем и номером изменения.
 */
public record BatchFaultState(boolean enabled, long appliedEpochMs, long revision) {
}
