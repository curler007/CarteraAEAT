package com.raul.bolsa.domain;

public enum AeatGroup {
    GROUP_1("1 - Mercado español"),
    GROUP_2("2 - Mercado europeo"),
    GROUP_3("3 - Mercado extraeuropeo");

    private final String label;

    AeatGroup(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /**
     * Prefijo de país del ISIN → grupo, para valores que el usuario aún no tiene. Es una
     * aproximación: el grupo lo fija dónde cotiza el valor, no dónde está domiciliado el emisor,
     * así que las importaciones solo lo usan cuando no hay ningún grupo previo que heredar.
     */
    public static AeatGroup forIsin(String isin) {
        String country = isin != null && isin.length() >= 2 ? isin.substring(0, 2).toUpperCase() : "";
        if ("ES".equals(country)) return GROUP_1;
        return EUROPEAN.contains(country) ? GROUP_2 : GROUP_3;
    }

    /** {@code XF} no es un país: es el prefijo de los identificadores sintéticos de cripto. */
    private static final java.util.Set<String> EUROPEAN = java.util.Set.of(
            "AT", "BE", "BG", "CH", "CY", "CZ", "DE", "DK", "EE", "FI", "FR", "GB", "GR", "HR",
            "HU", "IE", "IS", "IT", "LI", "LT", "LU", "LV", "MT", "NL", "NO", "PL", "PT", "RO",
            "SE", "SI", "SK", "XF");
}
