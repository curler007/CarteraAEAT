package com.raul.bolsa.web;

import com.raul.bolsa.domain.Operation;
import com.raul.bolsa.repository.OperationRepository;
import com.raul.bolsa.security.CurrentUser;
import com.raul.bolsa.service.QuoteService;
import com.raul.bolsa.web.dto.IsinStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@RestController
@RequiredArgsConstructor
public class IsinStatusController {

    private final OperationRepository operationRepo;
    private final QuoteService quoteService;
    private final CurrentUser currentUser;

    /**
     * Estado de cada ISIN de la cartera: si Yahoo sabe darle precio desde su primera operación.
     *
     * <p>Va en una sola llamada y no en una por valor porque comparte la sesión de consulta: los
     * ISIN repetidos se resuelven y se descargan una vez, no una por fila del listado.
     */
    @GetMapping("/api/isin-status")
    public List<IsinStatus> statuses() {
        Map<String, LocalDate> since = new TreeMap<>();
        for (Operation op : operationRepo.findByUserId(currentUser.id())) {
            since.merge(op.getAssetName(), op.getDate(),
                    (a, b) -> a.isBefore(b) ? a : b);
        }

        QuoteService.Historic historic = quoteService.openHistoric();
        List<IsinStatus> out = new ArrayList<>();
        since.forEach((isin, first) -> {
            var series = historic.seriesOf(isin);
            out.add(new IsinStatus(isin, first.toString(), series.isPresent(),
                    series.map(QuoteService.Series::symbol).orElseGet(() -> historic.symbolFor(isin)),
                    series.map(s -> s.from().toString()).orElse(null)));
        });
        out.sort(Comparator.comparing(IsinStatus::isin));
        return out;
    }
}
