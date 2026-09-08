package com.raul.bolsa.service;

import com.raul.bolsa.domain.IsinTwin;
import com.raul.bolsa.domain.Operation;
import com.raul.bolsa.repository.IsinTwinRepository;
import com.raul.bolsa.repository.OperationRepository;
import com.raul.bolsa.web.dto.TwinCandidate;
import com.raul.bolsa.web.dto.TwinCheck;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
    public TwinCheck setTwin(Long userId, String isin, String twin) {
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
        QuoteService.Historic historic = quoteService.openHistoric();
        IsinTwin checked = check(userId, isin, row, historic);
        return withPrices(historic, isin, checked);
    }

    /**
     * Añade al resultado los dos precios que permiten juzgar si el gemelo es el fondo correcto: el
     * suyo y el del listado que el ISIN resuelve por su cuenta, que casi siempre publica precio
     * aunque no publique serie. Si no hay con qué comparar, se dice, en vez de callarlo.
     */
    private TwinCheck withPrices(QuoteService.Historic historic, String isin, IsinTwin row) {
        Optional<QuoteService.Money> mine = row.getResolvedSymbol() == null
                ? Optional.empty()
                : historic.priceOf(row.getResolvedSymbol());
        String own = historic.symbolFor(isin);
        Optional<QuoteService.Money> reference = own == null || own.equals(row.getResolvedSymbol())
                ? Optional.empty()
                : historic.priceOf(own);

        BigDecimal drift = driftPct(historic, mine.orElse(null), reference.orElse(null));
        return new TwinCheck(row,
                mine.map(QuoteService.Money::amount).orElse(null),
                mine.map(QuoteService.Money::currency).orElse(null),
                reference.map(QuoteService.Money::amount).orElse(null),
                reference.map(QuoteService.Money::currency).orElse(null),
                drift);
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
                .map(hit -> {
                    var price = historic.priceOf(hit.symbol());
                    return new TwinCandidate(hit.symbol(), hit.name(), hit.exchange(),
                            historic.seriesOfSymbol(hit.symbol())
                                    .map(s -> s.from().toString())
                                    .orElse(null),
                            price.map(QuoteService.Money::amount).orElse(null),
                            price.map(QuoteService.Money::currency).orElse(null));
                })
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

    /**
     * Cuánto se separan dos precios, en porcentaje, pasando ambos a euros antes de restar.
     *
     * <p>La conversión no es un adorno: sin ella no se comparan justo los dos casos que importan.
     * Un listado correcto del mismo fondo puede cotizar en otra divisa —el de Fidelity cotiza en
     * dólares y el que resuelve el ISIN en euros— y un valor equivocado del todo también, con lo
     * que ni se avisaba del error ni se confirmaba el acierto.
     */
    private BigDecimal driftPct(QuoteService.Historic historic,
                                QuoteService.Money mine, QuoteService.Money reference) {
        if (mine == null || reference == null) return null;
        LocalDate today = LocalDate.now();
        Optional<BigDecimal> mineEur = historic.toEurAt(mine.amount(), mine.currency(), today);
        Optional<BigDecimal> refEur = historic.toEurAt(reference.amount(), reference.currency(), today);
        if (mineEur.isEmpty() || refEur.isEmpty() || refEur.get().signum() == 0) return null;
        return mineEur.get().subtract(refEur.get()).abs()
                .multiply(BigDecimal.valueOf(100))
                .divide(refEur.get().abs(), 2, RoundingMode.HALF_UP);
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
