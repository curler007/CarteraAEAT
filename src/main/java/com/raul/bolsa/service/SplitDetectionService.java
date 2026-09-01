package com.raul.bolsa.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.raul.bolsa.domain.Operation;
import com.raul.bolsa.domain.OperationType;
import com.raul.bolsa.domain.Split;
import com.raul.bolsa.repository.OperationRepository;
import com.raul.bolsa.repository.SplitRepository;
import com.raul.bolsa.web.dto.DetectedSplit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Propone splits publicados por Yahoo Finance que afectan a la cartera y aún no están dados de alta.
 *
 * <p>Un split solo se sugiere si el usuario tenía posición viva de ese valor en su fecha: los
 * anteriores a la primera compra —o los ocurridos entre una venta total y una recompra
 * posterior— no alteran ningún lote y por tanto no afectan al FIFO.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SplitDetectionService {

    /** Histórico completo de eventos de split del valor. */
    private static final String SPLIT_CHART_QUERY = "interval=1mo&range=max&events=split";

    /** Margen para dar por registrado un split ya presente con la fecha ligeramente desplazada. */
    private static final int DUPLICATE_TOLERANCE_DAYS = 5;

    /** Por debajo de esta posición se considera que no quedaban acciones (residuos de redondeo). */
    private static final BigDecimal MIN_POSITION = new BigDecimal("0.000001");

    /** Dentro de esta banda alrededor de 1 el evento suele ser un dividendo en acciones. */
    private static final BigDecimal SCRIP_LOW = new BigDecimal("0.95");
    private static final BigDecimal SCRIP_HIGH = new BigDecimal("1.05");

    private static final long CACHE_TTL_MILLIS = 6 * 60 * 60 * 1000L;

    private final OperationRepository operationRepo;
    private final SplitRepository splitRepo;
    private final QuoteService quoteService;
    private final SplitService splitService;

    /** Evita repetir la consulta a Yahoo en cada refresco de la página. Clave: ISIN. */
    private final Map<String, CachedSplits> cache = new ConcurrentHashMap<>();

    private record YahooSplit(LocalDate date, BigDecimal ratio, String label) {}

    private record CachedSplits(long fetchedAt, String symbol, List<YahooSplit> splits) {
        boolean expired() {
            return System.currentTimeMillis() - fetchedAt > CACHE_TTL_MILLIS;
        }
    }

    /** Splits detectados para el usuario, del más reciente al más antiguo. */
    public List<DetectedSplit> detect(Long userId) {
        Map<String, List<Operation>> opsByTicker = operationRepo.findByUserId(userId).stream()
                .collect(Collectors.groupingBy(op -> op.getTicker().toUpperCase()));

        Map<String, List<Split>> registeredByTicker = splitRepo.findByUserId(userId).stream()
                .collect(Collectors.groupingBy(s -> s.getTicker().toUpperCase()));

        List<DetectedSplit> detected = new ArrayList<>();
        for (Map.Entry<String, List<Operation>> entry : opsByTicker.entrySet()) {
            List<Operation> ops = entry.getValue();
            List<Split> registered = registeredByTicker.getOrDefault(entry.getKey(), List.of());

            String isin = ops.get(0).getAssetName();
            CachedSplits fetched = fetchSplits(isin);
            if (fetched == null) continue;

            for (YahooSplit ys : fetched.splits()) {
                if (alreadyRegistered(registered, ys.date())) continue;
                BigDecimal position = positionBefore(ops, registered, ys.date());
                if (position.compareTo(MIN_POSITION) <= 0) continue;
                detected.add(new DetectedSplit(
                        ops.get(0).getTicker(), isin, fetched.symbol(),
                        ys.date(), ys.ratio(), ys.label(), position, isScrip(ys.ratio())));
            }
        }
        detected.sort(Comparator.comparing(DetectedSplit::date).reversed()
                .thenComparing(DetectedSplit::ticker));
        return detected;
    }

    /**
     * Acciones del valor en cartera justo antes de {@code at}, expresadas en términos de esa fecha.
     *
     * <p>Solo cuentan las operaciones estrictamente anteriores: una compra del mismo día que el
     * split no se ve afectada por él, igual que en {@code FifoService.recalculateFifo}, donde el
     * split se aplica antes que las operaciones de su misma fecha. Las cantidades se ajustan por
     * los splits ya registrados para no mezclar acciones de épocas distintas.
     */
    private BigDecimal positionBefore(List<Operation> ops, List<Split> registered, LocalDate at) {
        LocalDate upTo = at.minusDays(1);
        BigDecimal total = BigDecimal.ZERO;
        for (Operation op : ops) {
            if (!op.getDate().isBefore(at)) continue;
            BigDecimal qty = op.getQuantity()
                    .multiply(splitService.cumulativeFactor(registered, op.getDate(), upTo));
            total = op.getType() == OperationType.SELL ? total.subtract(qty) : total.add(qty);
        }
        return total;
    }

    private boolean alreadyRegistered(List<Split> registered, LocalDate date) {
        return registered.stream().anyMatch(s ->
                Math.abs(ChronoUnit.DAYS.between(s.getDate(), date)) <= DUPLICATE_TOLERANCE_DAYS);
    }

    private boolean isScrip(BigDecimal ratio) {
        return ratio.compareTo(SCRIP_LOW) > 0 && ratio.compareTo(SCRIP_HIGH) < 0;
    }

    /** Consulta el histórico de splits del ISIN, con caché. Devuelve null si no se pudo resolver. */
    private CachedSplits fetchSplits(String isin) {
        CachedSplits cached = cache.get(isin);
        if (cached != null && !cached.expired()) return cached;

        for (String symbol : quoteService.candidateSymbols(isin)) {
            try {
                JsonNode result = quoteService.fetchChartResult(symbol, SPLIT_CHART_QUERY).orElse(null);
                if (result == null) continue;
                CachedSplits fresh = new CachedSplits(
                        System.currentTimeMillis(), symbol, parseSplits(result));
                cache.put(isin, fresh);
                return fresh;
            } catch (Exception e) {
                log.warn("No se pudieron obtener splits de {} ({}): {}", isin, symbol, e.getMessage());
            }
        }
        log.debug("Sin símbolo de Yahoo para {}", isin);
        return null;
    }

    private List<YahooSplit> parseSplits(JsonNode result) {
        List<YahooSplit> splits = new ArrayList<>();
        for (JsonNode ev : result.path("events").path("splits")) {
            BigDecimal numerator = ev.path("numerator").decimalValue();
            BigDecimal denominator = ev.path("denominator").decimalValue();
            if (numerator.signum() <= 0 || denominator.signum() <= 0) continue;
            LocalDate date = Instant.ofEpochSecond(ev.path("date").asLong())
                    .atZone(ZoneOffset.UTC).toLocalDate();
            BigDecimal ratio = numerator.divide(denominator, 8, RoundingMode.HALF_UP);
            splits.add(new YahooSplit(date, ratio, ev.path("splitRatio").asText()));
        }
        return splits;
    }

    /** Descarta la caché para que la siguiente detección vuelva a consultar Yahoo. */
    public void clearCache() {
        cache.clear();
    }
}
