package com.raul.bolsa;

import com.raul.bolsa.domain.FifoLot;
import com.raul.bolsa.web.dto.PeriodBasis;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * El punto de partida de cada periodo de la cabecera del dashboard.
 *
 * <p>Lo que ya estaba en cartera se valorará al precio de aquella fecha; lo comprado dentro del
 * periodo entra por su coste, de modo que meter dinero nuevo no se contabilice como ganancia.
 */
class PeriodBasisTest {

    private static final LocalDate FROM = LocalDate.parse("2026-08-03");

    @Test
    @DisplayName("Lo comprado antes del periodo cuenta como títulos; lo de dentro, como coste")
    void splitsPositionByPurchaseDate() {
        Map<String, PeriodBasis> basis = PeriodBasis.byIsin(List.of(
                lot("US0378331005", "2025-01-15", "10", "1500"),   // ya la tenía
                lot("US0378331005", "2026-08-20", "2", "600")      // comprada dentro del periodo
        ), FROM);

        PeriodBasis apple = basis.get("US0378331005");
        assertEquals(0, new BigDecimal("10").compareTo(apple.heldQty()),
                "Solo los títulos anteriores se valoran a precio de mercado de la fecha");
        assertEquals(0, new BigDecimal("600").compareTo(apple.addedCost()),
                "La compra de dentro del periodo entra por lo que costó");
    }

    @Test
    @DisplayName("Una posición entera anterior al periodo no aporta coste")
    void olderPositionIsAllQuantity() {
        PeriodBasis b = PeriodBasis.byIsin(List.of(
                lot("ES0171996087", "2024-03-01", "138", "1250")), FROM).get("ES0171996087");

        assertEquals(0, new BigDecimal("138").compareTo(b.heldQty()));
        assertEquals(0, BigDecimal.ZERO.compareTo(b.addedCost()));
    }

    @Test
    @DisplayName("Una posición entera comprada dentro del periodo parte de su coste")
    void newerPositionIsAllCost() {
        PeriodBasis b = PeriodBasis.byIsin(List.of(
                lot("US84615Q1031", "2026-08-28", "3", "900")), FROM).get("US84615Q1031");

        assertEquals(0, BigDecimal.ZERO.compareTo(b.heldQty()),
                "Sin títulos previos no hay nada que valorar al precio de aquella fecha");
        assertEquals(0, new BigDecimal("900").compareTo(b.addedCost()),
                "Su variación en el periodo es la plusvalía desde que se compró, ni más ni menos");
    }

    @Test
    @DisplayName("Una compra del mismo día de la referencia cuenta como comprada dentro")
    void purchaseOnTheReferenceDayCountsAsAdded() {
        PeriodBasis b = PeriodBasis.byIsin(List.of(
                lot("US67066G1040", "2026-08-03", "4", "800")), FROM).get("US67066G1040");

        assertEquals(0, new BigDecimal("800").compareTo(b.addedCost()));
    }

    @Test
    @DisplayName("Cada valor se contabiliza por separado")
    void keepsIsinsApart() {
        Map<String, PeriodBasis> basis = PeriodBasis.byIsin(List.of(
                lot("US0378331005", "2025-01-15", "10", "1500"),
                lot("ES0173516115", "2026-08-20", "50", "700")
        ), FROM);

        assertEquals(2, basis.size());
        assertEquals(0, new BigDecimal("10").compareTo(basis.get("US0378331005").heldQty()));
        assertEquals(0, new BigDecimal("700").compareTo(basis.get("ES0173516115").addedCost()));
        assertTrue(basis.get("ES0173516115").heldQty().signum() == 0);
    }

    private static FifoLot lot(String isin, String date, String qty, String cost) {
        FifoLot lot = new FifoLot();
        lot.setAssetName(isin);
        lot.setPurchaseDate(LocalDate.parse(date));
        lot.setRemainingQty(new BigDecimal(qty));
        lot.setRemainingCost(new BigDecimal(cost));
        return lot;
    }
}
