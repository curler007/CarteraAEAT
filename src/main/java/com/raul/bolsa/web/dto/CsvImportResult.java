package com.raul.bolsa.web.dto;

import java.util.List;

/**
 * Resultado de un intento de importación.
 * Si {@code errors} no está vacío no se ha tocado nada: la validación es previa a la escritura.
 */
public record CsvImportResult(int operations, int splits, List<String> errors) {

    public boolean ok() {
        return errors.isEmpty();
    }

    public static CsvImportResult failed(List<String> errors) {
        return new CsvImportResult(0, 0, errors);
    }
}
