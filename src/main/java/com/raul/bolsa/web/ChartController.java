package com.raul.bolsa.web;

import com.raul.bolsa.security.CurrentUser;
import com.raul.bolsa.service.PortfolioValuationService;
import com.raul.bolsa.web.dto.PortfolioHistory;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.Set;

@Controller
@RequiredArgsConstructor
public class ChartController {

    private final PortfolioValuationService valuation;
    private final CurrentUser currentUser;

    @GetMapping("/chart")
    public String chart() {
        return "chart";
    }

    /**
     * La serie de los últimos {@code years} años. Va aparte de la página por lo mismo que
     * {@code /api/period-basis}: necesita los históricos de Yahoo, y la página se pinta antes con
     * la ruedita girando.
     *
     * <p>Solo las ventanas que ofrece la página. Yahoo se pide con once años de serie, así que
     * cualquiera hasta diez cabría, pero cada año son 365 puntos más que valorar y pintar, y una
     * ventana arbitraria en la URL no aporta nada.
     */
    @GetMapping("/api/portfolio-history")
    @ResponseBody
    public PortfolioHistory history(@RequestParam(defaultValue = "1") int years) {
        if (!WINDOWS.contains(years)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ventana no admitida: " + years);
        }
        LocalDate today = LocalDate.now();
        return valuation.history(currentUser.id(), today.minusYears(years), today);
    }

    private static final Set<Integer> WINDOWS = Set.of(1, 2, 5, 10);
}
