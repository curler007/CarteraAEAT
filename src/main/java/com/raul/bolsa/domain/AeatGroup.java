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
}
