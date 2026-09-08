package com.raul.bolsa.web.dto;

import com.raul.bolsa.domain.IsinTwin;

import java.math.BigDecimal;

/**
 * Resultado de comprobar un gemelo, con lo necesario para juzgar si además es el fondo correcto.
 *
 * <p>Que un símbolo tenga histórico no dice nada de si es tu fondo: {@code V60A.SA} podría existir
 * y ser otra cosa completamente distinta. La única pista barata es el precio, comparado con el del
 * listado que el propio ISIN resuelve en Yahoo, que suele publicar precio aunque no publique
 * serie. Dos listados del mismo fondo cotizan casi igual.
 *
 * @param reference precio del listado propio del ISIN, o null si no hay con qué comparar
 * @param driftPct  cuánto se separan los dos precios, en porcentaje, o null si falta alguno
 */
public record TwinCheck(IsinTwin row, BigDecimal price, String currency,
                        BigDecimal reference, String referenceCurrency, BigDecimal driftPct) {}
