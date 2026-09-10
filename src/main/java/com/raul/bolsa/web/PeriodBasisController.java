package com.raul.bolsa.web;

import com.raul.bolsa.security.CurrentUser;
import com.raul.bolsa.service.PortfolioValuationService;
import com.raul.bolsa.web.dto.PeriodBaseline;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class PeriodBasisController {

    private final PortfolioValuationService valuation;
    private final CurrentUser currentUser;

    /**
     * Punto de partida de cada periodo de la cabecera, con la cartera reconstruida a esa fecha.
     *
     * <p>Va en una llamada aparte y no en el modelo de la página porque necesita precios
     * históricos: dejar que el dashboard espere a Yahoo para pintarse sería cambiar un número malo
     * por una página lenta. La primera visita del día paga la descarga; el resto salen de la caché.
     *
     * <p>El día de hoy no está aquí. Su punto de partida es el cierre anterior de cada posición,
     * que ya viene con la cotización, y en una sesión la cartera de ayer es la de hoy.
     */
    @GetMapping("/api/period-basis")
    public List<PeriodBaseline> baselines() {
        LocalDate today = LocalDate.now();
        Map<String, LocalDate> dates = new LinkedHashMap<>();
        dates.put("week", today.minusWeeks(1));
        dates.put("month", today.minusMonths(1));
        dates.put("year", today.minusYears(1));
        return valuation.baselines(currentUser.id(), dates);
    }
}
