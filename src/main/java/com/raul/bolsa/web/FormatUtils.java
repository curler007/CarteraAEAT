package com.raul.bolsa.web;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Utilidades de formateo accesibles en Thymeleaf como {@code ${@fmt.qty(value)}}.
 */
@Component("fmt")
public class FormatUtils {

    /**
     * Formatea una cantidad con máximo 6 decimales y sin ceros finales.
     * Ejemplos:
     *   36.00000000 → "36"
     *   0.42100000  → "0.421"
     *   1.500000    → "1.5"
     */
    public String qty(BigDecimal value) {
        if (value == null) return "";
        return value.setScale(6, RoundingMode.HALF_UP)
                    .stripTrailingZeros()
                    .toPlainString();
    }
}
