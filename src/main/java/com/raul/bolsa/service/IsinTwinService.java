package com.raul.bolsa.service;

import com.raul.bolsa.domain.IsinTwin;
import com.raul.bolsa.domain.Operation;
import com.raul.bolsa.repository.IsinTwinRepository;
import com.raul.bolsa.repository.OperationRepository;
import com.raul.bolsa.web.dto.TwinCandidate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Sabe qué símbolo de Yahoo le corresponde a cada ISIN de la cartera, y se acuerda.
 *
 * <p>Comprobar un ISIN cuesta dos llamadas a Yahoo, así que el resultado se guarda. Lo que ya
 * resolvió no se vuelve a preguntar nunca: un fondo que publica histórico no deja de publicarlo.
 * Lo que no resolvió sí se reintenta en cada visita, porque es lo que cambia cuando se le pone un
 * gemelo o cuando Yahoo empieza a listarlo.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class IsinTwinService {

    /** Cuántos listados pedirle a Yahoo. Con uno solo es con lo que la app venía fallando. */
    private static final int CANDIDATES = 10;

    private final IsinTwinRepository twinRepo;
    private final OperationRepository operationRepo;
    private final QuoteService quoteService;

    /** Estado de todos los ISIN de la cartera, resolviendo por el camino los que falten. */
    @Transactional
    public List<IsinTwin> statuses(Long userId) {
        Map<String, IsinTwin> known = twinRepo.findByUserId(userId).stream()
                .collect(Collectors.toMap(IsinTwin::getIsin, Function.identity(), (a, b) -> a));

        List<String> isins = operationRepo.findByUserId(userId).stream()
                .map(Operation::getAssetName)
                .distinct()
                .sorted()
                .toList();

        QuoteService.Historic historic = quoteService.openHistoric();
        Map<String, IsinTwin> out = new TreeMap<>();
        for (String isin : isins) {
            IsinTwin row = known.get(isin);
            if (row != null && row.isResolved()) {
                out.put(isin, row);
                continue;
            }
            out.put(isin, check(userId, isin, row, historic));
        }
        return out.values().stream().sorted(Comparator.comparing(IsinTwin::getIsin)).toList();
    }

    /**
     * Fija el gemelo de un ISIN y lo comprueba en el acto, para que quien lo escribe vea enseguida
     * si sirve. Un gemelo vacío borra el que hubiera y vuelve a dejar que resuelva Yahoo.
     */
    @Transactional
    public IsinTwin setTwin(Long userId, String isin, String twin) {
        IsinTwin row = twinRepo.findByUserIdAndIsin(userId, isin).orElseGet(() -> {
            IsinTwin fresh = new IsinTwin();
            fresh.setUserId(userId);
            fresh.setIsin(isin);
            return fresh;
        });
        String cleaned = twin == null || twin.isBlank() ? null : twin.trim();
        row.setTwin(cleaned);
        // Lo aprendido con el símbolo anterior ya no vale
        row.setResolvedSymbol(null);
        row.setHistoryFrom(null);
        return check(userId, isin, row, quoteService.openHistoric());
    }

    /**
     * Candidatos a gemelo de un ISIN, cada uno con el histórico que publica, para poder elegir.
     *
     * <p>Se comprueban todos, que son unas cuantas descargas, así que va bajo demanda y nunca al
     * pintar la lista. Los que sirven salen primero, y entre ellos el de histórico más largo, que
     * es el que menos periodos dejará sin cubrir.
     */
    public List<TwinCandidate> candidates(String isin) {
        QuoteService.Historic historic = quoteService.openHistoric();
        return quoteService.search(isin, CANDIDATES).stream()
                .map(hit -> new TwinCandidate(hit.symbol(), hit.name(), hit.exchange(),
                        historic.seriesOfSymbol(hit.symbol())
                                .map(s -> s.from().toString())
                                .orElse(null)))
                .sorted(Comparator.comparing(TwinCandidate::usable).reversed()
                        .thenComparing(c -> c.historyFrom() == null ? "9999" : c.historyFrom()))
                .toList();
    }

    /** El gemelo de un ISIN, si alguien se lo puso. Lo consulta QuoteService al cotizar. */
    public Optional<String> twinOf(Long userId, String isin) {
        return twinRepo.findByUserIdAndIsin(userId, isin)
                .map(IsinTwin::getTwin)
                .filter(t -> t != null && !t.isBlank());
    }

    private IsinTwin newRow(Long userId, String isin) {
        IsinTwin row = new IsinTwin();
        row.setUserId(userId);
        row.setIsin(isin);
        return row;
    }

    private IsinTwin check(Long userId, String isin, IsinTwin existing, QuoteService.Historic historic) {
        final IsinTwin row = existing != null ? existing : newRow(userId, isin);
        Optional<QuoteService.Series> series = row.getTwin() != null
                ? historic.seriesOfSymbol(row.getTwin())
                : historic.seriesOf(isin);

        row.setResolvedSymbol(series.map(QuoteService.Series::symbol)
                .orElseGet(() -> row.getTwin() != null ? row.getTwin() : historic.symbolFor(isin)));
        row.setHistoryFrom(series.map(QuoteService.Series::from).orElse(null));
        row.setCheckedAt(LocalDate.now());
        return twinRepo.save(row);
    }
}
