package com.raul.bolsa.service;

import com.raul.bolsa.domain.Operation;
import com.raul.bolsa.domain.OperationType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * El nombre con el que el desglose del periodo enseña cada valor.
 *
 * <p>Sale de todas las operaciones del valor y no solo de las del periodo. Mirando únicamente las
 * del tramo, una semana en la que no se compró ni vendió nada dejaba sin nombre a toda la cartera
 * y el desglose enseñaba el ISIN dos veces.
 */
class PeriodNamesTest {

    @Test
    @DisplayName("Cada ISIN se enseña con su nombre, aunque no tenga operaciones en el periodo")
    void everyIsinGetsItsName() {
        Map<String, String> names = PortfolioValuationService.tickersByIsin(List.of(
                op("2020-01-15", "APPLE", "US0378331005"),
                op("2021-03-01", "GRIFOLS", "ES0171996087")));

        assertEquals("APPLE", names.get("US0378331005"));
        assertEquals("GRIFOLS", names.get("ES0171996087"));
    }

    @Test
    @DisplayName("Un fondo renombrado se enseña con el nombre nuevo")
    void renamedFundKeepsTheLatestName() {
        Map<String, String> names = PortfolioValuationService.tickersByIsin(List.of(
                op("2024-06-10", "FONDO A INDEX P ACC EUR", "IE00000000A1"),
                op("2025-10-13", "FONDO A INDEX P2 ACC EUR", "IE00000000A1")));

        assertEquals("FONDO A INDEX P2 ACC EUR", names.get("IE00000000A1"),
                "el nombre vigente es el de la operación más reciente, no el de la primera compra");
    }

    private static Operation op(String date, String ticker, String isin) {
        Operation o = new Operation();
        o.setDate(LocalDate.parse(date));
        o.setType(OperationType.BUY);
        o.setTicker(ticker);
        o.setAssetName(isin);
        return o;
    }
}
