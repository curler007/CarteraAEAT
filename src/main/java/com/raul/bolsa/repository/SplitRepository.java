package com.raul.bolsa.repository;

import com.raul.bolsa.domain.Split;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SplitRepository extends JpaRepository<Split, Long> {

    List<Split> findByTickerOrderByDateAscIdAsc(String ticker);
}
