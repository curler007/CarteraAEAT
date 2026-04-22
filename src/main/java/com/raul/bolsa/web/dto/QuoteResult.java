package com.raul.bolsa.web.dto;

import java.math.BigDecimal;

/** price: precio más actualizado disponible (regularMarketPrice si existe, si no previousClose), siempre en EUR o en divisa original si no se pudo convertir */
public record QuoteResult(String symbol, BigDecimal price, String originalCurrency, boolean convertedToEur) {}
