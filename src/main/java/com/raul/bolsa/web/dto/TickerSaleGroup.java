package com.raul.bolsa.web.dto;

import com.raul.bolsa.domain.SaleRecord;

import java.math.BigDecimal;
import java.util.List;

public record TickerSaleGroup(
        String ticker,
        String assetName,
        String aeatGroupLabel,
        BigDecimal totalQuantity,
        BigDecimal totalCost,
        BigDecimal totalProceeds,
        BigDecimal totalGains,
        BigDecimal totalLosses,
        List<SaleRecord> records
) {}
