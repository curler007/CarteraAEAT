package com.raul.bolsa.repository;

import com.raul.bolsa.domain.Operation;
import com.raul.bolsa.domain.OperationType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OperationRepository extends JpaRepository<Operation, Long> {

    List<Operation> findAllByOrderByDateDescIdDesc();

    List<Operation> findByTypeOrderByDateDescIdDesc(OperationType type);

    List<Operation> findByTickerAndTypeOrderByDateAscIdAsc(String ticker, OperationType type);

    List<Operation> findByTickerOrderByDateAscIdAsc(String ticker);

    boolean existsByTickerAndTypeAndPendingQtyGreaterThan(String ticker, OperationType type, java.math.BigDecimal qty);

    /** ¿Hay alguna venta pendiente para este ticker con fecha estrictamente anterior a la dada? */
    boolean existsByTickerAndTypeAndPendingQtyGreaterThanAndDateBefore(
            String ticker, OperationType type, java.math.BigDecimal qty, java.time.LocalDate date);
}
