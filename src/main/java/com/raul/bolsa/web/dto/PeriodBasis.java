package com.raul.bolsa.web.dto;

import com.raul.bolsa.domain.FifoLot;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Punto de partida de una posición para medir la variación de un periodo.
 *
 * <p>Se separa en dos partes porque no todo lo que hay hoy en cartera estaba al empezar el
 * periodo: lo que ya se tenía se valora al precio de mercado de aquella fecha, y lo comprado
 * después entra por lo que costó. Así una compra reciente no se apunta la subida que el valor
 * tuvo antes de comprarla, y aportar dinero no cuenta como ganancia.
 *
 * @param heldQty   títulos que ya estaban en cartera antes de la fecha de referencia
 * @param addedCost coste de adquisición de los que entraron a partir de esa fecha
 */
public record PeriodBasis(BigDecimal heldQty, BigDecimal addedCost) {

    public static final PeriodBasis EMPTY = new PeriodBasis(BigDecimal.ZERO, BigDecimal.ZERO);

    /** Reparte cada posición viva entre lo que ya se tenía en {@code from} y lo comprado después. */
    public static Map<String, PeriodBasis> byIsin(List<FifoLot> openLots, LocalDate from) {
        Map<String, PeriodBasis> basis = new HashMap<>();
        for (FifoLot lot : openLots) {
            basis.compute(lot.getAssetName(), (isin, current) -> {
                PeriodBasis b = current == null ? EMPTY : current;
                return lot.getPurchaseDate().isBefore(from)
                        ? new PeriodBasis(b.heldQty().add(lot.getRemainingQty()), b.addedCost())
                        : new PeriodBasis(b.heldQty(), b.addedCost().add(lot.getRemainingCost()));
            });
        }
        return basis;
    }
}
