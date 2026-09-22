package com.raul.bolsa.web.dto;

import java.util.List;
import java.util.Map;

/**
 * Lectura de un Activity Statement de Interactive Brokers.
 *
 * @param operations compras y ventas de acciones y ETFs listas para guardar
 * @param ignored    operaciones del extracto que no entran en la cartera, contadas por categoría
 *                   de activo: opciones, futuros, divisas o bonos
 * @param duplicates operaciones omitidas por estar ya en la cartera, sea porque se importaron en
 *                   un extracto anterior o porque el usuario las había apuntado a mano
 * @param errors     filas que no se han podido interpretar, con su número de línea. Mientras haya
 *                   una sola no se importa nada: el extracto es el histórico entero de la cuenta
 *                   y media cartera cargada es peor que ninguna
 */
public record IbkrParseResult(
        List<OperationForm> operations,
        Map<String, Integer> ignored,
        int duplicates,
        List<String> errors
) {
    /** Total de operaciones del extracto que se han quedado fuera de la cartera. */
    public int ignoredCount() {
        return ignored.values().stream().mapToInt(Integer::intValue).sum();
    }

    public static IbkrParseResult failed(List<String> errors) {
        return new IbkrParseResult(List.of(), Map.of(), 0, errors);
    }
}
