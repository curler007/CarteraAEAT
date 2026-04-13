package com.raul.bolsa.domain;

public enum OperationType {
    BUY("Compra"),
    SELL("Venta"),
    CANJE("Canje");

    private final String label;

    OperationType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
