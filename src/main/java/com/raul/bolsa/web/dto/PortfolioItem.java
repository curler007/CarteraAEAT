package com.raul.bolsa.web.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record PortfolioItem(
        String ticker,
        String assetName,
        BigDecimal totalQty,
        BigDecimal totalCost
) {
    public BigDecimal avgCost() {
        if (totalQty.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
        return totalCost.divide(totalQty, 6, RoundingMode.HALF_UP);
    }
}
