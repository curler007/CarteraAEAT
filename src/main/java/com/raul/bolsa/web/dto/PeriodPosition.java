package com.raul.bolsa.web.dto;

import java.math.BigDecimal;

/**
 * Un valor dentro de un periodo: lo que valía al empezar y lo que entró y salió de él después.
 *
 * <p>Con esto y el valor de hoy sale su contribución al periodo, que es
 * {@code hoy + outflow − openingValue − inflow}. Sumadas todas, más el desajuste de los traspasos
 * en tránsito, dan exactamente la variación de la tarjeta: ver {@link PeriodBaseline#transferDrift}.
 *
 * <p>Y esa contribución se parte en dos, sin que sobre ni falte nada:
 * <ul>
 *   <li><b>lo que salió</b>: {@code outflow − openingSold − unmatched}, lo que se cobró por lo
 *       vendido o traspasado menos lo que eso mismo valía al empezar el periodo;</li>
 *   <li><b>lo que sigue dentro</b>: {@code hoy − (openingValue − openingSold) − (inflow − unmatched)}.</li>
 * </ul>
 *
 * @param openingValue valor de mercado de la posición aquel día, cero si no se tenía todavía
 * @param openingSold  la parte de {@code openingValue} que corresponde a los títulos que salieron
 *                     después. Lo que sale se descuenta primero de lo que ya se tenía, que es la
 *                     misma regla FIFO con la que se lleva toda la cartera
 */
public record PeriodPosition(String isin, String ticker, BigDecimal openingValue,
                             BigDecimal openingSold, BigDecimal inflow, BigDecimal outflow,
                             BigDecimal unmatched) {}
