package com.raul.bolsa.web.dto;

/**
 * Si Yahoo Finance publica cotizaciones de un ISIN, y con qué símbolo.
 *
 * @param twin        símbolo gemelo puesto a mano, o null si nadie lo ha puesto
 * @param found       Yahoo publica histórico utilizable: es lo que hace falta para los periodos
 * @param symbol      símbolo con el que se cotiza, o el que resolvió Yahoo aunque no sirviera
 * @param historyFrom primer día con cierre publicado, o null si no hay serie
 */
public record IsinStatus(String isin, String twin, boolean found, String symbol, String historyFrom) {}
