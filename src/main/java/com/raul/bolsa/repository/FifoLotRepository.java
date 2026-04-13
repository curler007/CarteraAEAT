package com.raul.bolsa.repository;

import com.raul.bolsa.domain.FifoLot;
import com.raul.bolsa.web.dto.PortfolioItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface FifoLotRepository extends JpaRepository<FifoLot, Long> {

    /**
     * Lotes con cantidad restante > 0 para un ticker, ordenados por fecha de compra ASC (FIFO).
     */
    List<FifoLot> findByTickerAndRemainingQtyGreaterThanAndPurchaseDateLessThanEqualOrderByPurchaseDateAscIdAsc(
            String ticker, BigDecimal minQty, java.time.LocalDate maxDate);

    List<FifoLot> findByTickerOrderByPurchaseDateAscIdAsc(String ticker);

    Optional<FifoLot> findByOperation_Id(Long operationId);

    /**
     * Resumen de cartera: agrupa lotes activos por ticker.
     */
    @Query("""
            SELECT new com.raul.bolsa.web.dto.PortfolioItem(
                f.ticker,
                f.assetName,
                SUM(f.remainingQty),
                SUM(f.remainingCost)
            )
            FROM FifoLot f
            WHERE f.remainingQty > 0
            GROUP BY f.ticker, f.assetName
            ORDER BY SUM(f.remainingCost) DESC
            """)
    List<PortfolioItem> findPortfolioSummary();
}
