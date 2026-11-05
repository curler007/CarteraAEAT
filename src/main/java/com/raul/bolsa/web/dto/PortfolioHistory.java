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
 * @param estimated valores que algún día de la ventana no tuvieron cierre en Yahoo y se valoraron
 *                al precio de su última operación, por su nombre
 * @param missing valores que algún día no se pudieron valorar de ninguna manera, por su nombre: ni
 *                cierre ni operación con importe. En esos días el valor sale corto y la gráfica
 *                pinta una pérdida que no existe
 * @param sales   las ventas de la ventana, para marcar en la gráfica el beneficio que se llevan
 * @param breakdown el valor repartido entre los mayores valores de hoy y el resto, un importe por
 *                  punto; vacío si no se pidió
 */
public record PortfolioHistory(List<Point> points, List<String> estimated, List<String> missing,
                               List<Sale> sales, List<Holding> breakdown) {

    /**
     * @param cost     lo que costó lo que había en cartera ese día
     * @param complete false si ese día algún valor se valoró sin cierre de Yahoo, o no se valoró
     */
    public record Point(String date, BigDecimal value, BigDecimal cost, boolean complete) {}

    /**
     * @param proceeds lo cobrado, neto de comisión
     * @param gainLoss su resultado según el FIFO, el mismo que se declara
     */
    public record Sale(String date, String ticker, BigDecimal proceeds, BigDecimal gainLoss) {}

    /**
     * Lo que valió un valor cada día de la ventana, alineado con {@code points}. El último del
     * desglose es el resto, y entre todos suman el valor de la cartera de cada día.
     *
     * @param isin   null en el resto
     * @param costs  lo que costaba ese mismo día lo que quedaba del valor; entre todos suman el
     *               coste de la cartera. La diferencia con {@code values} es su beneficio
     */
    public record Holding(String name, String isin, List<BigDecimal> values, List<BigDecimal> costs) {}
}
