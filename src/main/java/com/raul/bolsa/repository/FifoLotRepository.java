package com.raul.bolsa.repository;

import com.raul.bolsa.domain.FifoLot;
import com.raul.bolsa.web.dto.PortfolioItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface FifoLotRepository extends JpaRepository<FifoLot, Long> {

    /**
     * Lotes del usuario con cantidad restante > 0 para un ticker,
     * ordenados por fecha de compra ASC (FIFO).
     */
    List<FifoLot> findByUserIdAndTickerAndRemainingQtyGreaterThanAndPurchaseDateLessThanEqualOrderByPurchaseDateAscIdAsc(
            Long userId, String ticker, BigDecimal minQty, LocalDate maxDate);

    List<FifoLot> findByUserIdAndTickerOrderByPurchaseDateAscIdAsc(Long userId, String ticker);

    List<FifoLot> findByUserId(Long userId);

    Optional<FifoLot> findByOperation_Id(Long operationId);

    void deleteByUserId(Long userId);

    /**
     * Resumen de cartera del usuario: agrupa sus lotes activos por ticker.
     */
    @Query("""
            SELECT new com.raul.bolsa.web.dto.PortfolioItem(
                f.ticker,
                f.assetName,
                SUM(f.remainingQty),
                SUM(f.remainingCost)
            )
            FROM FifoLot f
            WHERE f.remainingQty > 0 AND f.userId = :userId
            GROUP BY f.ticker, f.assetName
            ORDER BY SUM(f.remainingCost) DESC
            """)
    List<PortfolioItem> findPortfolioSummary(Long userId);
}
