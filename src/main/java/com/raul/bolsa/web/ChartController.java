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
     * La serie de los últimos {@code years} años, o desde la primera operación con {@code max}.
     * Va aparte de la página por lo mismo que {@code /api/period-basis}: necesita los históricos
     * de Yahoo, y la página se pinta antes con la ruedita girando.
     *
     * <p>Solo las ventanas que ofrece la página: cada año son 365 puntos más que valorar y pintar,
     * y una ventana arbitraria en la URL no aporta nada.
     *
     * <p>El desglose por valor solo va si se pide: con diez años son diez series de 3.650 puntos,
     * y la gráfica normal no tiene por qué cargarlas.
     */
    @GetMapping("/api/portfolio-history")
    @ResponseBody
    public PortfolioHistory history(@RequestParam(defaultValue = "1") String years,
                                    @RequestParam(defaultValue = "false") boolean breakdown) {
        Long userId = currentUser.id();
        LocalDate today = LocalDate.now();
        if (MAX.equals(years)) {
            // Sin operaciones no hay "desde el principio": se enseña el año por defecto, vacío.
            LocalDate first = valuation.firstOperationDate(userId).orElse(today.minusYears(1));
            return valuation.history(userId, first.isAfter(today) ? today : first, today, breakdown);
        }
        if (!WINDOWS.contains(years)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ventana no admitida: " + years);
        }
        return valuation.history(userId, today.minusYears(Integer.parseInt(years)), today, breakdown);
    }

    private static final String MAX = "max";
    private static final Set<String> WINDOWS = Set.of("1", "2", "5", "10");
}
