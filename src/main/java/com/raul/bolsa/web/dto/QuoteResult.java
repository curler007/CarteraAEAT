package com.raul.bolsa.web.dto;

import java.math.BigDecimal;

/**
 * price: precio más actualizado disponible (regularMarketPrice si existe, si no previousClose), siempre en EUR o en divisa original si no se pudo convertir
 * previousClose: cierre de la sesión anterior a la de {@code price}, en la misma divisa; null si no se pudo determinar
 * closeWeek / closeMonth / closeYear: último cierre de hace una semana, un mes y un año, para medir
 *   la variación de esos periodos; null cuando el valor no cotizaba entonces o la serie no llega
 */
public record QuoteResult(String symbol, BigDecimal price, BigDecimal previousClose,
                          BigDecimal closeWeek, BigDecimal closeMonth, BigDecimal closeYear,
                          String originalCurrency, boolean convertedToEur) {}
