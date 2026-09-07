package com.raul.bolsa.web.dto;

/**
 * Si Yahoo Finance publica cotizaciones de un ISIN.
 *
 * <p>Sirve para poder corregir el dato: un ISIN mal escrito, o el de una clase de participaciones
 * que Yahoo solo lista en un mercado secundario sin histórico, deja la posición sin variaciones
 * por periodo, y hasta ahora eso no se veía por ningún sitio.
 *
 * @param since       primera operación del valor, para poder juzgar si el histórico llega o no
 * @param symbol      símbolo al que Yahoo resuelve el ISIN, o null si no resuelve a ninguno
 * @param historyFrom primer día con cierre publicado, o null si Yahoo no publica serie
 */
public record IsinStatus(String isin, String since, boolean found, String symbol, String historyFrom) {}
