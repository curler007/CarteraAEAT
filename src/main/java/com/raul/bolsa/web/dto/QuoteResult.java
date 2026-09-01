package com.raul.bolsa.web.dto;

import java.math.BigDecimal;

/**
 * price: precio más actualizado disponible (regularMarketPrice si existe, si no previousClose), siempre en EUR o en divisa original si no se pudo convertir
 * previousClose: cierre de la sesión anterior a la de {@code price}, en la misma divisa; null si no se pudo determinar
 */
public record QuoteResult(String symbol, BigDecimal price, BigDecimal previousClose,
                          String originalCurrency, boolean convertedToEur) {}
