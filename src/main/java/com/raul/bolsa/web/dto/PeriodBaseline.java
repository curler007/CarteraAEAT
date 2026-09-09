package com.raul.bolsa.web.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Punto de partida de un periodo, con la cartera reconstruida tal y como estaba aquel día.
 *
 * <p>Se calcula desde las operaciones y no desde los lotes vivos. Un lote de traspaso conserva la
 * fecha de adquisición del fondo de origen —correcto para el FIFO y para Hacienda— pero lleva el
 * ISIN del destino, así que mirándolo parecería que ese fondo se tenía desde mucho antes de que el
 * dinero llegase a él, y se valoraría a precios de cuando ni siquiera estaba ahí.
 *
 * @param openingValue valor de mercado de lo que había en cartera aquel día, en euros
 * @param boughtAfter  dinero nuevo que entró después: no es ganancia y no debe contar como tal
 * @param soldAfter    dinero que salió por ventas después, que sigue siendo del inversor
 * @param missing      valores que había aquel día y que no se han podido valorar. Mientras la
 *                     lista no esté vacía el periodo no es publicable: el valor inicial sale corto
 *                     y la variación, inflada
 */
public record PeriodBaseline(
        String period,
        String at,
        BigDecimal openingValue,
        BigDecimal boughtAfter,
        BigDecimal soldAfter,
        List<String> missing
) {}
