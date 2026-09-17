package com.raul.bolsa.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Qué parte del valor inicial de una posición se fue con lo que salió durante el periodo.
 *
 * <p>Es la bisagra entre las dos mitades de la contribución de un valor —lo que se llevó lo
 * vendido y lo que sigue latente en lo que queda— y de ella depende que las dos sumen la
 * contribución entera y no un número parecido.
 */
class PeriodSoldShareTest {

    @Test
    @DisplayName("Sale la parte proporcional de los títulos que se fueron")
    void proportionalToTheSharesThatLeft() {
        BigDecimal parte = PortfolioValuationService.soldShareOf(
                new BigDecimal("1000"), new BigDecimal("100"), new BigDecimal("40"));

        assertEquals(0, new BigDecimal("400").compareTo(parte),
                "40 de 100 títulos se llevan el 40 % del valor de aquel día");
    }

    @Test
    @DisplayName("No se puede llevar más valor inicial del que había")
    void neverMoreThanWhatWasHeld() {
        // Vendió los 100 que tenía, volvió a comprar dentro del periodo y vendió otros 50: por el
        // valor inicial solo pueden pasar los 100 primeros. El resto lo explican las compras.
        BigDecimal parte = PortfolioValuationService.soldShareOf(
                new BigDecimal("1000"), new BigDecimal("100"), new BigDecimal("150"));

        assertEquals(0, new BigDecimal("1000").compareTo(parte),
                "el valor inicial se agota, no se multiplica");
    }

    @Test
    @DisplayName("Sin posición aquel día no hay valor inicial que repartir")
    void nothingHeldMeansNothingSold() {
        assertEquals(0, BigDecimal.ZERO.compareTo(PortfolioValuationService.soldShareOf(
                        new BigDecimal("1000"), BigDecimal.ZERO, new BigDecimal("10"))),
                "lo comprado y vendido dentro del periodo no toca el punto de partida");
        assertEquals(0, BigDecimal.ZERO.compareTo(PortfolioValuationService.soldShareOf(
                        new BigDecimal("1000"), new BigDecimal("100"), BigDecimal.ZERO)),
                "si no salió nada, todo el valor inicial sigue dentro");
    }
}
