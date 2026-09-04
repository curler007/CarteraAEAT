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

    /**
     * Operaciones que entraron sin coste conocido, en orden cronológico. Se excluyen los CANJE,
     * cuyo coste cero es correcto por definición.
     */
    List<Operation> findByUserIdAndTypeNotAndTotalLessThanEqualOrderByDateAscIdAsc(
            Long userId, OperationType type, BigDecimal total);

    /** Acceso por id comprobando propietario: evita que un usuario toque datos de otro. */
    Optional<Operation> findByIdAndUserId(Long id, Long userId);

    boolean existsByUserIdAndTickerAndTypeAndPendingQtyGreaterThan(
            Long userId, String ticker, OperationType type, BigDecimal qty);

    /**
     * ¿Hay alguna salida pendiente de este usuario para este ticker con fecha anterior a la dada?
     * Cuenta tanto la venta como la salida de un traspaso: las dos sacan títulos, y dejar pasar
     * una por delante de otra pendiente rompería el orden FIFO.
     */
    boolean existsByUserIdAndTickerAndTypeInAndPendingQtyGreaterThanAndDateBefore(
            Long userId, String ticker, List<OperationType> types, BigDecimal qty, LocalDate date);

    /** Todas las operaciones del usuario en orden cronológico, para el replay global. */
    List<Operation> findByUserIdOrderByDateAscIdAsc(Long userId);

    /** ¿La cartera del usuario contiene traspasos, que obligan a recalcular en bloque? */
    boolean existsByUserIdAndTypeIn(Long userId, List<OperationType> types);

    void deleteByUserId(Long userId);
}
