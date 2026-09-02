package com.raul.bolsa.web.dto;

import java.util.List;
import java.util.Map;

/**
 * Lectura de una exportación de Trade Republic.
 *
 * @param operations compras y ventas listas para guardar
 * @param ignored    movimientos descartados por no mover posiciones, contados por "categoría / tipo"
 * @param duplicates operaciones omitidas por estar ya importadas (mismo transaction_id)
 * @param errors     filas de compra/venta que no se han podido interpretar, con su línea
 */
public record TradeRepublicParseResult(
        List<OperationForm> operations,
        Map<String, Integer> ignored,
        int duplicates,
        List<String> errors
) {
    /** Total de movimientos descartados por no ser compras ni ventas. */
    public int ignoredCount() {
        return ignored.values().stream().mapToInt(Integer::intValue).sum();
    }
}
