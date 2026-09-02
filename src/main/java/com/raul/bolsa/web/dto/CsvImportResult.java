package com.raul.bolsa.web.dto;

import java.util.List;
import java.util.Map;

/**
 * Resultado de un intento de importación.
 * Si {@code errors} no está vacío no se ha tocado nada: la validación es previa a la escritura.
 *
 * @param format     formato detectado en el fichero, para confirmárselo al usuario
 * @param ignored    movimientos descartados por no mover posiciones, contados por tipo
 *                   (solo en los ficheros de broker; vacío en el formato propio)
 * @param duplicates operaciones omitidas por estar ya importadas
 */
public record CsvImportResult(
        String format,
        int operations,
        int splits,
        Map<String, Integer> ignored,
        int duplicates,
        List<String> errors
) {
    public boolean ok() {
        return errors.isEmpty();
    }

    public int ignoredCount() {
        return ignored.values().stream().mapToInt(Integer::intValue).sum();
    }

    public static CsvImportResult failed(List<String> errors) {
        return new CsvImportResult("", 0, 0, Map.of(), 0, errors);
    }
}
