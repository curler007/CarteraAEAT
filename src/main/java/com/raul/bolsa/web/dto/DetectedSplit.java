package com.raul.bolsa.web.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Split publicado por Yahoo Finance que afecta a la cartera y todavía no está registrado.
 *
 * @param ticker      ticker propio de la cartera (el que se guardará en el Split)
 * @param assetName   ISIN con el que se consultó
 * @param symbol      símbolo de Yahoo que resolvió el ISIN
 * @param ratio       factor multiplicador ya normalizado: 10 para 10:1, 0.1 para 1:10
 * @param ratioLabel  ratio tal y como lo publica Yahoo ("10:1")
 * @param position    acciones en cartera justo antes del split, en términos de esa fecha
 * @param likelyScrip ratio muy próximo a 1: suele ser un dividendo en acciones, no un split
 */
public record DetectedSplit(
        String ticker,
        String assetName,
        String symbol,
        LocalDate date,
        BigDecimal ratio,
        String ratioLabel,
        BigDecimal position,
        boolean likelyScrip
) {
}
