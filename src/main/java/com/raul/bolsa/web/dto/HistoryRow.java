package com.raul.bolsa.web.dto;

import com.raul.bolsa.domain.Operation;
import com.raul.bolsa.domain.Split;

import java.time.LocalDate;

/**
 * Fila unificada para el historial de operaciones.
 * Exactamente uno de {operation, split} es no nulo.
 */
public record HistoryRow(LocalDate date, Operation operation, Split split) {
}
