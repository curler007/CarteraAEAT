package com.raul.bolsa.repository;

import com.raul.bolsa.domain.Split;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface SplitRepository extends JpaRepository<Split, Long> {

    List<Split> findByUserIdAndTickerOrderByDateAscIdAsc(Long userId, String ticker);

    List<Split> findByUserId(Long userId);

    /**
     * ¿Hay algún split de este ticker posterior a la fecha dada? Una operación que se inserte
     * antes de un split ya registrado necesita que se rehaga el FIFO: su lote nace sin ese
     * split aplicado y se quedaría con menos títulos de los que corresponden.
     */
    boolean existsByUserIdAndTickerAndDateAfter(Long userId, String ticker, LocalDate date);

    List<Split> findByUserId(Long userId, Sort sort);

    Optional<Split> findByIdAndUserId(Long id, Long userId);

    void deleteByUserId(Long userId);
}
