package com.raul.bolsa.repository;

import com.raul.bolsa.domain.FxRate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FxRateRepository extends JpaRepository<FxRate, Long> {

    /**
     * La serie entera de una divisa. Son unos 7.000 días por divisa desde 1999, que es lo que ya
     * se tenía en memoria: leerla de golpe al arrancar cuesta menos que consultarla fecha a fecha
     * mientras se importa un extracto con decenas de fechas distintas.
     */
    List<FxRate> findByCurrency(String currency);

    List<FxRate> findAllByOrderByCurrencyAscRateDateAsc();
}
