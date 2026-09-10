package com.raul.bolsa.service;

import com.raul.bolsa.domain.Operation;
import com.raul.bolsa.domain.OperationType;
import com.raul.bolsa.domain.Split;
import com.raul.bolsa.repository.OperationRepository;
import com.raul.bolsa.repository.SplitRepository;
import com.raul.bolsa.web.dto.PeriodBaseline;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Reconstruye cuánto valía la cartera en una fecha pasada.
 *
 * <p>No escribe nada: lee operaciones y splits, pregunta precios y devuelve números. Los lotes
 * FIFO y los registros de venta no se tocan ni se leen.
 *
 * <p>La cartera de un día sale de las operaciones anteriores a esa fecha, nunca de los lotes
 * vivos: los lotes de un traspaso heredan la fecha del fondo de origen conservando el ISIN del
 * destino, y usarlos haría creer que se tenía un fondo antes de que el dinero llegara a él.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PortfolioValuationService {

    private final OperationRepository operationRepo;
    private final SplitRepository splitRepo;
    private final SplitService splitService;
    private final QuoteService quoteService;
    private final IsinTwinService twinService;

    /**
     * Punto de partida de cada periodo pedido. Comparten una sola ventana de consulta, que a su
     * vez se apoya en la caché del día: los mismos valores aparecen en varios periodos y así se
     * descargan una vez por jornada, no una por periodo ni una por visita.
     */
    public List<PeriodBaseline> baselines(Long userId, Map<String, LocalDate> dates) {
        List<Operation> operations = operationRepo.findByUserId(userId);
        Map<String, List<Split>> splits = splitRepo.findByUserId(userId).stream()
                .collect(Collectors.groupingBy(s -> s.getTicker().toUpperCase()));
        QuoteService.Historic historic = quoteService.openHistoric();
        LocalDate today = LocalDate.now();

        Map<String, String> symbols = new HashMap<>();
        List<PeriodBaseline> out = new ArrayList<>();
        dates.forEach((period, at) ->
                out.add(baseline(period, operations, splits, historic, symbols, userId, at, today)));
        return out;
    }

    /** Títulos por ISIN en poder del inversor en {@code at}, cargando sus operaciones y splits. */
    public Map<String, BigDecimal> holdingsAt(Long userId, LocalDate at) {
        return holdingsAt(operationRepo.findByUserId(userId),
                splitRepo.findByUserId(userId).stream()
                        .collect(Collectors.groupingBy(s -> s.getTicker().toUpperCase())),
                at, LocalDate.now());
    }

    private PeriodBaseline baseline(String period, List<Operation> operations,
                                    Map<String, List<Split>> splits, QuoteService.Historic historic,
                                    Map<String, String> symbols, Long userId,
                                    LocalDate at, LocalDate today) {
        BigDecimal value = BigDecimal.ZERO;
        List<String> missing = new ArrayList<>();

        for (Map.Entry<String, BigDecimal> position : holdingsAt(operations, splits, at, today).entrySet()) {
            String isin = position.getKey();
            String symbol = symbols.computeIfAbsent(isin, k -> symbolOf(historic, userId, k));
            BigDecimal eur = symbol == null ? null : valueOf(historic, symbol, position.getValue(), at);
            if (eur == null) missing.add(isin);
            else value = value.add(eur);
        }

        missing.sort(Comparator.naturalOrder());
        return new PeriodBaseline(period, at.toString(), scaled(value),
                scaled(sumAfter(operations, at, OperationType.BUY)),
                scaled(sumAfter(operations, at, OperationType.SELL)),
                missing);
    }

    /** El gemelo si lo tiene, y si no el listado que resuelva Yahoo: la misma regla que al cotizar. */
    private String symbolOf(QuoteService.Historic historic, Long userId, String isin) {
        return twinService.twinOf(userId, isin)
                .or(() -> historic.seriesOf(isin).map(QuoteService.Series::symbol))
                .orElse(null);
    }

    /**
     * Títulos por ISIN en poder del inversor en {@code at}, expresados en las acciones de hoy.
     *
     * <p>El ajuste por splits es obligatorio: los cierres históricos de Yahoo vienen ya ajustados,
     * así que una cantidad anterior a un split no se puede multiplicar por ellos sin traducirla
     * antes a los mismos términos.
     */
    private Map<String, BigDecimal> holdingsAt(List<Operation> operations, Map<String, List<Split>> splits,
                                               LocalDate at, LocalDate today) {
        Map<String, BigDecimal> held = new LinkedHashMap<>();
        Map<String, BigDecimal> factors = new HashMap<>();
        for (Operation op : operations) {
            if (op.getDate().isAfter(at)) continue;
            String ticker = op.getTicker().toUpperCase();
            BigDecimal factor = factors.computeIfAbsent(ticker + "@" + op.getDate(),
                    k -> splitService.cumulativeFactor(
                            splits.getOrDefault(ticker, List.of()), op.getDate(), today));
            BigDecimal qty = op.getQuantity().multiply(factor);
            held.merge(op.getAssetName(), op.getType().reducesPosition() ? qty.negate() : qty, BigDecimal::add);
        }
        held.values().removeIf(q -> q.signum() <= 0);
        return held;
    }

    /** Valor en euros de una posición en una fecha, o null si falta el precio o el cambio. */
    private BigDecimal valueOf(QuoteService.Historic historic, String symbol,
                               BigDecimal qty, LocalDate at) {
        Optional<QuoteService.Money> price = historic.closeAt(symbol, at);
        if (price.isEmpty()) return null;
        return historic.toEurAt(qty.multiply(price.get().amount()), price.get().currency(), at)
                .orElse(null);
    }

    private BigDecimal sumAfter(List<Operation> operations, LocalDate at, OperationType type) {
        return operations.stream()
                .filter(op -> op.getType() == type && op.getDate().isAfter(at))
                .map(Operation::getTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal scaled(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
