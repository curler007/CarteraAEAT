package com.raul.bolsa.web.dto;

import java.math.BigDecimal;

/**
 * Un movimiento de dinero real de la cartera, visto desde el bolsillo: negativo cuando sale para
 * comprar, positivo cuando vuelve de una venta. Es lo que necesita la TIR.
 *
 * <p>Los traspasos no son flujos de caja y no aparecen aquí: no entra ni sale dinero, es el mismo
 * coste cambiando de fondo.
 *
 * @param date fecha en ISO, que es como la entiende el navegador sin ayuda
 */
public record CashFlow(String date, BigDecimal amount) {}
