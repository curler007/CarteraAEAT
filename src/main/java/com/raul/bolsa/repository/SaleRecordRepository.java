package com.raul.bolsa.repository;

import com.raul.bolsa.domain.SaleRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SaleRecordRepository extends JpaRepository<SaleRecord, Long> {

    List<SaleRecord> findByTaxYearOrderBySaleDateAscTickerAsc(int taxYear);

    List<SaleRecord> findAllByOrderBySaleDateDescIdDesc();

    boolean existsBySellOperation_Id(Long operationId);

    boolean existsByConsumedLot_Operation_Id(Long operationId);

    List<SaleRecord> findBySellOperation_Id(Long operationId);

    boolean existsByTickerAndSaleDateGreaterThanEqual(String ticker, java.time.LocalDate date);

    void deleteByTicker(String ticker);
}
