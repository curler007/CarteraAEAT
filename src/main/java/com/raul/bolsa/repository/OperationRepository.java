package com.raul.bolsa.repository;

import com.raul.bolsa.domain.Operation;
import com.raul.bolsa.domain.OperationType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Todas las consultas van filtradas por {@code userId}: la cartera de cada usuario es
 * independiente y el FIFO nunca puede cruzar de uno a otro.
 */
public interface OperationRepository extends JpaRepository<Operation, Long> {

    List<Operation> findAllByUserIdOrderByDateDescIdDesc(Long userId);

    List<Operation> findByUserIdAndTickerOrderByDateAscIdAsc(Long userId, String ticker);

    List<Operation> findByUserId(Long userId);

    /** Acceso por id comprobando propietario: evita que un usuario toque datos de otro. */
    Optional<Operation> findByIdAndUserId(Long id, Long userId);

    boolean existsByUserIdAndTickerAndTypeAndPendingQtyGreaterThan(
            Long userId, String ticker, OperationType type, BigDecimal qty);

    /** ¿Hay alguna venta pendiente de este usuario para este ticker con fecha anterior a la dada? */
    boolean existsByUserIdAndTickerAndTypeAndPendingQtyGreaterThanAndDateBefore(
            Long userId, String ticker, OperationType type, BigDecimal qty, LocalDate date);

    void deleteByUserId(Long userId);
}
