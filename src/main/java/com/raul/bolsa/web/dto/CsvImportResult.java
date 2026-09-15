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
 * @param pendingValuation entregas dadas de alta a coste cero, pendientes de que el usuario
 *                         les ponga su valor de adquisición real
 * @param warnings   avisos sobre lo importado: se ha guardado, pero con alguna salvedad que el
 *                   usuario tiene que conocer para interpretar bien las cifras
 * @param conflicts  operaciones que ya estaban pero con datos distintos. Mientras haya alguna no
 *                   se escribe nada: es el usuario quien decide si cada una se queda como está o
 *                   se actualiza con lo que trae el fichero
 */
public record CsvImportResult(
        String format,
        int operations,
        int splits,
        Map<String, Integer> ignored,
        int duplicates,
        List<String> pendingValuation,
        List<String> warnings,
        List<String> errors,
        List<ImportConflict> conflicts
) {
    public boolean ok() {
        return errors.isEmpty() && conflicts.isEmpty();
    }

    /** Hay decisiones pendientes: no se ha tocado nada y hay que preguntar antes de seguir. */
    public boolean needsDecision() {
        return errors.isEmpty() && !conflicts.isEmpty();
    }

    public int ignoredCount() {
        return ignored.values().stream().mapToInt(Integer::intValue).sum();
    }

    public static CsvImportResult failed(List<String> errors) {
        return new CsvImportResult("", 0, 0, Map.of(), 0, List.of(), List.of(), errors, List.of());
    }

    /** Nada escrito: primero hay que resolver estos conflictos. */
    public static CsvImportResult undecided(String format, List<ImportConflict> conflicts) {
        return new CsvImportResult(format, 0, 0, Map.of(), 0, List.of(), List.of(),
                List.of(), conflicts);
    }
}
