package com.raul.bolsa.web.dto;

import java.math.BigDecimal;

/**
 * Un candidato a gemelo: lo que Yahoo ofrece para un ISIN, ya comprobado.
 *
 * <p>Se comprueba antes de enseñarlo porque si no la lista no ayudaría: el problema de partida es
 * justo que varios de los listados que devuelve Yahoo no publican histórico, y elegir a ciegas
 * entre ellos es lo mismo que se hace ahora.
 *
 * <p>Lleva el precio a propósito. Que un símbolo tenga histórico no dice nada de si es tu fondo, y
 * comparar precios es la forma barata de verlo: dos listados del mismo fondo cotizan casi igual.
 *
 * @param historyFrom primer día con cierre, o null si ese listado no publica serie
 */
public record TwinCandidate(String symbol, String name, String exchange, String historyFrom,
                            BigDecimal price, String currency) {

    public boolean usable() {
        return historyFrom != null;
    }
}
