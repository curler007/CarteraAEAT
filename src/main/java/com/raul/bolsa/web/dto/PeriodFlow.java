package com.raul.bolsa.web.dto;

import java.math.BigDecimal;

/**
 * Lo que entró y salió de un valor después de una fecha, leído solo de las operaciones.
 *
 * <p>Las dos patas de un traspaso cuentan aquí como entrada y salida, y en los totales del periodo
 * no: para la cartera un traspaso no es dinero nuevo ni dinero recuperado, pero para el valor que
 * lo recibe sí es dinero que llegó, y para el que lo suelta es valor que se fue. Sin ese matiz la
 * salida parecería una pérdida del tamaño del fondo entero y la entrada, una ganancia igual.
 *
 * @param inflow    compras, entradas de traspaso y lo que apareció sin compra detrás
 * @param outflow   ventas y salidas de traspaso
 * @param unmatched la parte de {@code inflow} que no viene de una compra sino de una salida sin
 *                  lote que la respaldara (ver {@link MissingOrigin}). Va aparte porque no es
 *                  dinero que el inversor metiera: es el agujero del histórico que falta, y al
 *                  partir la contribución tiene que irse con la salida que lo destapó
 * @param outQty    títulos que salieron, en las acciones de hoy: es lo que dice qué parte del
 *                  valor inicial se fue y qué parte sigue dentro
 */
public record PeriodFlow(String isin, String ticker, BigDecimal inflow, BigDecimal outflow,
                         BigDecimal unmatched, BigDecimal outQty) {}
