package com.raul.bolsa.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lectura de la serie de tipos del Data Portal del BCE, sin tocar la red: lo que se comprueba es
 * el formato de la respuesta, que es lo que puede cambiar por debajo.
 */
class EcbFxRateTest {

    /** Respuesta real de {@code EXR/D.USD.EUR.SP00.A?format=csvdata&detail=dataonly}. */
    private static final String CSV = """
            KEY,FREQ,CURRENCY,CURRENCY_DENOM,EXR_TYPE,EXR_SUFFIX,TIME_PERIOD,OBS_VALUE
            EXR.D.USD.EUR.SP00.A,D,USD,EUR,SP00,A,1999-01-04,1.1789
            EXR.D.USD.EUR.SP00.A,D,USD,EUR,SP00,A,2025-09-05,1.1697
            EXR.D.USD.EUR.SP00.A,D,USD,EUR,SP00,A,2026-09-02,1.1578
            """;

    @Test
    @DisplayName("Se queda con la fecha y el tipo, y descarta la cabecera")
    void parsesSeries() {
        Map<LocalDate, BigDecimal> rates = EcbFxRateService.parse(CSV);

        assertEquals(3, rates.size());
        assertEquals(0, new BigDecimal("1.1697").compareTo(rates.get(LocalDate.of(2025, 9, 5))));
        assertEquals(0, new BigDecimal("1.1578").compareTo(rates.get(LocalDate.of(2026, 9, 2))));
    }

    @Test
    @DisplayName("Una respuesta vacía o rota no revienta: devuelve una serie sin datos")
    void toleratesBadInput() {
        assertTrue(EcbFxRateService.parse(null).isEmpty());
        assertTrue(EcbFxRateService.parse("").isEmpty());
        assertTrue(EcbFxRateService.parse("<html>Service unavailable</html>").isEmpty());
    }
}
