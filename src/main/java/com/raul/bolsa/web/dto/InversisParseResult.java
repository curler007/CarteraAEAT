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
 * @param orphanTransfers traspasos de los que solo ha llegado una de las dos patas, descritos
 *                        para poder avisar: su coste no se puede heredar y se asume el valor de
 *                        entrada, que es una aproximación
 * @param errors       filas que no se han podido interpretar, con su número de línea
 */
public record InversisParseResult(
        List<OperationForm> operations,
        Map<String, Integer> ignored,
        int duplicates,
        List<String> orphanTransfers,
        List<String> errors
) {
    public int ignoredCount() {
        return ignored.values().stream().mapToInt(Integer::intValue).sum();
    }
}
