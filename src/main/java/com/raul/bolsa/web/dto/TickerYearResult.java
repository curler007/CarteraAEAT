package com.raul.bolsa.web.dto;

import java.math.BigDecimal;

public record TickerYearResult(
        String ticker,
        String assetName,
        BigDecimal totalCost,
        BigDecimal gainLoss,
        BigDecimal gainLossPercent
) {}
