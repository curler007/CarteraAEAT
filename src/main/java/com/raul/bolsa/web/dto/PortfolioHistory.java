package com.raul.bolsa.web.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Lo que valió la cartera cada día de una ventana, frente a lo que costó lo que había en ella.
 *
 * <p>El coste es el de la tarjeta Global —compras menos el coste FIFO de lo vendido—, así que la
 * distancia entre las dos curvas es la plusvalía latente, y el primer día de la ventana ya enseña
 * la que se traía de antes. Al vender, el beneficio baja por lo realizado: la venta lo saca de la
 * cartera aunque siga siendo del inversor, y por eso cada una se marca con su resultado.
 *
 * @param points  un punto por día natural, del primero al último de la ventana
 * @param missing valores que algún día de la ventana no se pudieron valorar, por su nombre. En
 *                esos días el valor sale corto y la gráfica pinta una pérdida que no existe
 * @param sales   las ventas de la ventana, para marcar en la gráfica el beneficio que se llevan
 */
public record PortfolioHistory(List<Point> points, List<String> missing, List<Sale> sales) {

    /**
     * @param cost     lo que costó lo que había en cartera ese día
     * @param complete false si ese día faltó el precio de algún valor
     */
    public record Point(String date, BigDecimal value, BigDecimal cost, boolean complete) {}

    /**
     * @param proceeds lo cobrado, neto de comisión
     * @param gainLoss su resultado según el FIFO, el mismo que se declara
     */
    public record Sale(String date, String ticker, BigDecimal proceeds, BigDecimal gainLoss) {}
}
