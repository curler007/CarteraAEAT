package com.raul.bolsa.repository;

import com.raul.bolsa.domain.IsinTwin;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface IsinTwinRepository extends JpaRepository<IsinTwin, Long> {

    List<IsinTwin> findByUserId(Long userId);

    Optional<IsinTwin> findByUserIdAndIsin(Long userId, String isin);
}
