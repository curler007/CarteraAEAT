package com.raul.bolsa.web.dto;

/**
 * Un candidato a gemelo: lo que Yahoo ofrece para un ISIN, ya comprobado.
 *
 * <p>Se comprueba antes de enseñarlo porque si no la lista no ayudaría: el problema de partida es
 * justo que varios de los listados que devuelve Yahoo no publican histórico, y elegir a ciegas
 * entre ellos es lo mismo que se hace ahora.
 *
 * @param historyFrom primer día con cierre, o null si ese listado no publica serie
 */
public record TwinCandidate(String symbol, String name, String exchange, String historyFrom) {

    public boolean usable() {
        return historyFrom != null;
    }
}
