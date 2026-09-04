package com.raul.bolsa.domain;

public enum OperationType {
    BUY("Compra"),
    SELL("Venta"),
    CANJE("Canje"),
    /** Salida de un traspaso entre fondos: se van títulos, pero su coste no se pierde. */
    TRASPASO_OUT("Traspaso (salida)"),
    /** Entrada de un traspaso entre fondos: los títulos nacen con el coste y la fecha del origen. */
    TRASPASO_IN("Traspaso (entrada)");

    private final String label;

    OperationType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** Reduce la posición del valor: consume lotes por FIFO. */
    public boolean reducesPosition() {
        return this == SELL || this == TRASPASO_OUT;
    }

    /** Las dos patas de un traspaso, que se resuelven emparejadas por {@code transferId}. */
    public boolean isTransfer() {
        return this == TRASPASO_OUT || this == TRASPASO_IN;
    }

    /**
     * Su coste sale de {@code total}, así que el lote se crea directamente al guardarla.
     * El {@code TRASPASO_IN} queda fuera: su coste lo hereda del origen y solo se conoce
     * al reproducir el traspaso completo.
     */
    public boolean createsOwnLot() {
        return this == BUY || this == CANJE;
    }
}
