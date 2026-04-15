package com.raul.bolsa.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Convierte LocalDate ↔ TEXT en SQLite de forma consistente.
 *
 * Escritura : "yyyy-MM-dd 00:00:00.0"  (formato que Hibernate-SQLite espera al leer)
 * Lectura   : acepta los tres formatos que pueden estar en la BD:
 *               1. "yyyy-MM-dd HH:mm:ss.S"  (formato canónico, registros nuevos)
 *               2. "yyyy-MM-dd"             (ISO puro, posible en migración)
 *               3. epoch ms como texto      (formato original pre-migración)
 */
@Converter(autoApply = true)
public class LocalDateConverter implements AttributeConverter<LocalDate, String> {

    private static final DateTimeFormatter CANONICAL =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.S");
    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ISO_LOCAL_DATE;

    @Override
    public String convertToDatabaseColumn(LocalDate date) {
        if (date == null) return null;
        return date.format(ISO) + " 00:00:00.0";
    }

    @Override
    public LocalDate convertToEntityAttribute(String value) {
        if (value == null || value.isBlank()) return null;

        // 1. Formato canónico "yyyy-MM-dd HH:mm:ss.S"
        try {
            return LocalDate.parse(value, CANONICAL);
        } catch (DateTimeParseException ignored) { }

        // 2. ISO puro "yyyy-MM-dd"
        try {
            return LocalDate.parse(value, ISO);
        } catch (DateTimeParseException ignored) { }

        // 3. Epoch ms (legado pre-migración)
        try {
            long ms = Long.parseLong(value.trim());
            return Instant.ofEpochMilli(ms).atZone(ZoneId.of("UTC")).toLocalDate();
        } catch (NumberFormatException ignored) { }

        throw new IllegalArgumentException("No se puede parsear la fecha: " + value);
    }
}
