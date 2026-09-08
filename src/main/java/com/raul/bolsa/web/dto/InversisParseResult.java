package com.raul.bolsa.web.dto;

import java.util.List;
import java.util.Map;

/**
 * Lectura del extracto de movimientos de Inversis, el depositario que hay detrás de las carteras
 * automatizadas de MyInvestor.
 *
 * @param operations   compras, ventas y patas de traspaso listas para guardar
 * @param ignored      movimientos descartados por no mover posiciones, contados por tipo
 * @param duplicates   operaciones omitidas por estar ya importadas
 * @param transferWarnings traspasos que no acaban de cuadrar: les falta una de las dos patas, o
 *                         lo que sale de unos fondos no coincide con lo que entra en otros
 * @param errors       filas que no se han podido interpretar, con su número de línea
 */
public record InversisParseResult(
        List<OperationForm> operations,
        Map<String, Integer> ignored,
        int duplicates,
        List<String> transferWarnings,
        List<String> errors
) {
    public int ignoredCount() {
        return ignored.values().stream().mapToInt(Integer::intValue).sum();
    }
}
