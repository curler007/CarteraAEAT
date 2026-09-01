package com.raul.bolsa.repository;

import com.raul.bolsa.domain.SaleRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface SaleRecordRepository extends JpaRepository<SaleRecord, Long> {

    List<SaleRecord> findByUserIdAndTaxYearOrderBySaleDateAscTickerAsc(Long userId, int taxYear);

    List<SaleRecord> findByUserId(Long userId);

    List<SaleRecord> findByUserIdAndSellOperation_Id(Long userId, Long operationId);

    boolean existsByUserIdAndConsumedLot_Operation_Id(Long userId, Long operationId);

    boolean existsByUserIdAndTickerAndSaleDateGreaterThanEqual(
            Long userId, String ticker, LocalDate date);

    void deleteByUserIdAndTicker(Long userId, String ticker);

    void deleteByUserId(Long userId);
}
