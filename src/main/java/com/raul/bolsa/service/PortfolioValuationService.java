package com.raul.bolsa.service;

import com.raul.bolsa.domain.Operation;
import com.raul.bolsa.domain.OperationType;
import com.raul.bolsa.domain.Split;
import com.raul.bolsa.repository.OperationRepository;
import com.raul.bolsa.repository.SplitRepository;
import com.raul.bolsa.web.dto.MissingOrigin;
import com.raul.bolsa.web.dto.PeriodBaseline;
import com.raul.bolsa.web.dto.PeriodFlow;
import com.raul.bolsa.web.dto.PeriodPosition;
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
        Map<String, BigDecimal> opening = new LinkedHashMap<>();
        Map<String, BigDecimal> heldAt = holdingsAt(operations, splits, at, today);

        for (Map.Entry<String, BigDecimal> position : heldAt.entrySet()) {
            String isin = position.getKey();
            String symbol = symbols.computeIfAbsent(isin, k -> symbolOf(historic, userId, k));
            BigDecimal eur = symbol == null ? null : valueOf(historic, symbol, position.getValue(), at);
            if (eur == null) missing.add(isin);
            else {
                value = value.add(eur);
                opening.put(isin, eur);
            }
        }

        missing.sort(Comparator.naturalOrder());
        return new PeriodBaseline(period, at.toString(), scaled(value),
                scaled(sumAfter(operations, at, OperationType.BUY)
                        .add(unmatchedAfter(operations, at))),
                scaled(sumAfter(operations, at, OperationType.SELL)),
                missing,
                positions(operations, splits, at, today, opening, heldAt),
                scaled(sumAfter(operations, at, OperationType.TRASPASO_IN)
                        .subtract(sumAfter(operations, at, OperationType.TRASPASO_OUT))));
    }

    /**
     * El periodo abierto valor a valor: lo que cada uno valía al empezar, lo que entró y salió de
     * él después, y cuánto de aquel valor inicial se fue por el camino. Incluye los que ya no
     * están en cartera —vendidos o traspasados dentro del periodo—, porque su movimiento explica
     * parte de la cifra igual que el de los que siguen.
     */
    private List<PeriodPosition> positions(List<Operation> operations, Map<String, List<Split>> splits,
                                           LocalDate at, LocalDate today,
                                           Map<String, BigDecimal> opening,
                                           Map<String, BigDecimal> heldAt) {
        Map<String, PeriodFlow> flows = flowsAfter(operations, splits, at, today);
        // El nombre sale de todas las operaciones del valor y no solo de las del periodo: en una
        // semana casi ninguna posición tiene movimientos, y sin esto el desglose enseñaba el ISIN
        // en la columna del nombre.
        Map<String, String> names = tickersByIsin(operations);
        Map<String, PeriodPosition> out = new LinkedHashMap<>();

        opening.forEach((isin, eur) -> out.put(isin, position(isin, names.getOrDefault(isin, isin), eur,
                soldShareOf(eur, heldAt.get(isin),
                        flows.containsKey(isin) ? flows.get(isin).outQty() : BigDecimal.ZERO),
                flows.get(isin))));
        // Los que no se tenían aquel día pero recibieron dinero después: una compra nueva, o el
        // fondo de destino de un traspaso. Su valor inicial es cero, no "falta el dato".
        flows.forEach((isin, flow) -> out.computeIfAbsent(isin, k ->
                position(isin, names.getOrDefault(isin, flow.ticker()),
                        BigDecimal.ZERO, BigDecimal.ZERO, flow)));
        return List.copyOf(out.values());
    }

    /**
     * Nombre con el que enseñar cada valor, por ISIN.
     *
     * <p>Gana el de la operación más reciente: un fondo que se renombró se lleva el nombre nuevo,
     * que es el que el usuario reconoce, y no el que tuviera la primera compra.
     */
    static Map<String, String> tickersByIsin(List<Operation> operations) {
        return operations.stream()
                .sorted(Comparator.comparing(Operation::getDate))
                .collect(Collectors.toMap(Operation::getAssetName, Operation::getTicker,
                        (older, newer) -> newer, LinkedHashMap::new));
    }

    private PeriodPosition position(String isin, String ticker, BigDecimal opening,
                                    BigDecimal openingSold, PeriodFlow flow) {
        return new PeriodPosition(isin, ticker, scaled(opening), scaled(openingSold),
                scaled(flow == null ? BigDecimal.ZERO : flow.inflow()),
                scaled(flow == null ? BigDecimal.ZERO : flow.outflow()),
                scaled(flow == null ? BigDecimal.ZERO : flow.unmatched()));
    }

    /**
     * Qué parte del valor inicial de una posición se fue con lo que salió después.
     *
     * <p>Lo que sale se descuenta primero de lo que ya se tenía, que es la misma regla FIFO con la
     * que se lleva la cartera entera: vender es deshacerse de lo más antiguo, no de lo que se
     * compró ayer. Si salió más de lo que había —porque después se compró y se volvió a vender— el
     * valor inicial se agota y el resto de esa salida es cosa de las compras del periodo.
     */
    static BigDecimal soldShareOf(BigDecimal openingValue, BigDecimal heldQty, BigDecimal outQty) {
        if (heldQty == null || heldQty.signum() <= 0 || outQty == null) return BigDecimal.ZERO;
        BigDecimal salieron = outQty.min(heldQty);
        if (salieron.signum() <= 0) return BigDecimal.ZERO;
        return openingValue.multiply(salieron).divide(heldQty, 6, RoundingMode.HALF_UP);
    }

    /**
     * Entradas y salidas de dinero por valor después de {@code at}, sin mirar un solo precio.
     *
     * <p>Las dos patas de un traspaso cuentan como salida en el fondo que lo suelta y entrada en el
     * que lo recibe. Para la cartera no son dinero nuevo —por eso los totales del periodo las
     * ignoran— pero para cada valor por separado sí lo son, y sin contarlas el fondo de origen
     * cargaría con una pérdida de su tamaño entero y el de destino con la ganancia simétrica.
     */
    public Map<String, PeriodFlow> flowsAfter(Long userId, LocalDate at) {
        return flowsAfter(operationRepo.findByUserId(userId),
                splitRepo.findByUserId(userId).stream()
                        .collect(Collectors.groupingBy(s -> s.getTicker().toUpperCase())),
                at, LocalDate.now());
    }

    private Map<String, PeriodFlow> flowsAfter(List<Operation> operations,
                                               Map<String, List<Split>> splits,
                                               LocalDate at, LocalDate today) {
        Map<String, PeriodFlow> flows = new LinkedHashMap<>();
        operations.stream()
                .filter(op -> op.getDate().isAfter(at))
                .sorted(Comparator.comparing(Operation::getDate))
                .forEach(op -> {
                    BigDecimal total = op.getTotal() == null ? BigDecimal.ZERO : op.getTotal();
                    boolean sale = op.getType().reducesPosition();
                    // La parte de una salida que no casó con ningún lote es valor que los libros
                    // no tenían: entra en la cartera aquí, igual que en el total del periodo.
                    BigDecimal unmatched = sale ? MissingOrigin.unmatchedValue(op) : BigDecimal.ZERO;
                    // Los títulos que salieron, en las acciones de hoy: el valor inicial con el
                    // que se comparan ya viene ajustado por los splits posteriores.
                    BigDecimal outQty = !sale ? BigDecimal.ZERO : op.getQuantity().multiply(
                            splitService.cumulativeFactor(
                                    splits.getOrDefault(op.getTicker().toUpperCase(), List.of()),
                                    op.getDate(), today));
                    flows.merge(op.getAssetName(),
                            new PeriodFlow(op.getAssetName(), op.getTicker(),
                                    sale ? unmatched : total, sale ? total : BigDecimal.ZERO,
                                    unmatched, outQty),
                            (a, b) -> new PeriodFlow(a.isin(), a.ticker(),
                                    a.inflow().add(b.inflow()), a.outflow().add(b.outflow()),
                                    a.unmatched().add(b.unmatched()), a.outQty().add(b.outQty())));
                });
        return flows;
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

    /**
     * Dinero que apareció en la cartera sin compra que lo explique, después de {@code at}.
     *
     * <p>Cuenta como dinero nuevo, igual que una compra, y esa es la única lectura posible: la
     * parte de una salida que no casó con ningún lote es valor que los libros no tenían y que a
     * partir de ese día sí tienen, porque la entrada del traspaso lo dio de alta como posición.
     * Sin sumarlo aquí se colaría entero en la variación del periodo, que mide precisamente el
     * valor de hoy contra el de entonces más lo aportado por el camino: un traspaso al que le
     * falta el origen se leería como una subida del mercado por su importe íntegro.
     *
     * @see com.raul.bolsa.web.dto.MissingOrigin
     */
    private BigDecimal unmatchedAfter(List<Operation> operations, LocalDate at) {
        return operations.stream()
                .filter(op -> op.getType().reducesPosition() && op.getDate().isAfter(at))
                .map(MissingOrigin::unmatchedValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal scaled(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
