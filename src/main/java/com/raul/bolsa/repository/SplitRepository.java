package com.raul.bolsa.repository;

import com.raul.bolsa.domain.Split;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SplitRepository extends JpaRepository<Split, Long> {

    List<Split> findByUserIdAndTickerOrderByDateAscIdAsc(Long userId, String ticker);

    List<Split> findByUserId(Long userId);

    List<Split> findByUserId(Long userId, Sort sort);

    Optional<Split> findByIdAndUserId(Long id, Long userId);

    void deleteByUserId(Long userId);
}
