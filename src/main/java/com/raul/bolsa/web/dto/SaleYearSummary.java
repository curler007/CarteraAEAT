package com.raul.bolsa.web.dto;

import java.math.BigDecimal;
import java.util.List;

public record SaleYearSummary(
        int year,
        List<TickerYearResult> tickers,
        BigDecimal yearGainLoss,
        BigDecimal yearCostBasis,
        BigDecimal yearGainLossPercent
) {}
