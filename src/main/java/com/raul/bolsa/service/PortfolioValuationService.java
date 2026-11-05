package com.raul.bolsa.service;

import com.raul.bolsa.domain.Operation;
import com.raul.bolsa.domain.OperationType;
import com.raul.bolsa.domain.SaleRecord;
import com.raul.bolsa.domain.Split;
import com.raul.bolsa.domain.FifoLot;
import com.raul.bolsa.repository.FifoLotRepository;
import com.raul.bolsa.repository.OperationRepository;
import com.raul.bolsa.repository.SaleRecordRepository;
import com.raul.bolsa.repository.SplitRepository;
import com.raul.bolsa.web.dto.MissingOrigin;
import com.raul.bolsa.web.dto.PeriodBaseline;
import com.raul.bolsa.web.dto.PeriodFlow;
import com.raul.bolsa.web.dto.PeriodPosition;
import com.raul.bolsa.web.dto.PortfolioHistory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
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
    private final SaleRecordRepository saleRecordRepo;
    private final FifoLotRepository fifoLotRepo;
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

    /**
     * Valor de la cartera y lo que costó lo que había en ella, día a día, de {@code from} a {@code to}.
     *
     * <p>El coste es el mismo que el de la tarjeta Global: compras menos el coste FIFO de lo
     * vendido. Sale de los registros de venta y no de los lotes, que solo guardan su estado de hoy.
     * Los traspasos no lo mueven, porque el coste pasa entero del fondo de origen al de destino.
     *
     * <p>Se calcula al vuelo y no se guarda: los cierres de Yahoo ya están en la caché del día —el
     * dashboard los pide para sus tarjetas— y con ellos esto es pura cuenta. Guardarlo obligaría a
     * invalidar a mano con cada operación, split, gemelo o importación, y un olvido dejaría la
     * gráfica mal sin que nada lo notara.
     *
     * <p>La cartera se lleva al día recorriendo las operaciones una sola vez, en vez de
     * reconstruirla desde cero para cada fecha como hace {@link #holdingsAt}: es la misma suma,
     * pero hecha 366 veces se notaba.
     */
    public PortfolioHistory history(Long userId, LocalDate from, LocalDate to) {
        return history(userId, from, to, false);
    }

    /** Puesto que hay que alcanzar algún día de la ventana para tener franja propia. */
    static final int BREAKDOWN_TOP = 9;

    /**
     * Franjas propias como mucho, por colores: más allá no se distinguen. Con los de "Todo" en una
     * cartera que ha rotado mucho se llega; lo que no quepa va al resto, empezando por lo que
     * menos llegó a valer.
     */
    static final int BREAKDOWN_MAX = 16;

    /**
     * Igual, y con {@code breakdown} además el valor de cada día repartido entre los valores que
     * algún día de la ventana estuvieron entre los {@value #BREAKDOWN_TOP} mayores, y el resto.
     *
     * <p>Basta con haberlo estado un día y no hace falta seguir hoy: un fondo que pesó un 20 % y
     * luego se traspasó explica parte de la curva igual que el que entró en su lugar. El orden es
     * fijo en toda la ventana —primero lo que más vale hoy, luego lo que ya no está, por lo que
     * llegó a valer—, porque reordenar por días haría saltar las franjas de sitio. El dinero en
     * tránsito y los valores pequeños van al resto, que se calcula como lo que falta hasta el
     * total: así las franjas suman la línea del valor al céntimo.
     */
    public PortfolioHistory history(Long userId, LocalDate from, LocalDate to, boolean breakdown) {
        List<Operation> operations = operationRepo.findByUserId(userId).stream()
                .sorted(Comparator.comparing(Operation::getDate).thenComparing(Operation::getId,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        Map<String, List<Split>> splits = splitRepo.findByUserId(userId).stream()
                .collect(Collectors.groupingBy(s -> s.getTicker().toUpperCase()));
        QuoteService.Historic historic = quoteService.openHistoric();
        LocalDate today = LocalDate.now();
        Map<String, String> symbols = new HashMap<>();
        Map<String, String> names = tickersByIsin(operations);
        List<SaleRecord> records = saleRecordRepo.findByUserId(userId);
        Map<Long, BigDecimal> costBySell = sumBySell(records, SaleRecord::getCostBasis);

        Map<String, BigDecimal> held = new HashMap<>();
        Map<String, BigDecimal> lastTradePrice = new HashMap<>();
        Set<String> estimated = new TreeSet<>();
        Set<String> missing = new TreeSet<>();
        List<PortfolioHistory.Point> points = new ArrayList<>();
        BigDecimal cost = BigDecimal.ZERO;
        int next = 0;
        int days = (int) ChronoUnit.DAYS.between(from, to) + 1;
        Map<String, BigDecimal[]> byIsin = new HashMap<>();
        // Lo que costaba lo que quedaba de cada valor, para partir su franja en invertido y
        // beneficio. Solo hace falta con el desglose.
        Map<String, BigDecimal> heldCost = new HashMap<>();
        Map<String, BigDecimal[]> costByIsin = new HashMap<>();
        Map<Long, BigDecimal> costByTransferIn = breakdown ? costByTransferIn(userId) : Map.of();

        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            // Lo que cambió el coste ese día. El primero arrastra además todo lo anterior a la
            // ventana: el coste de lo que se tenía hace un año viene de compras de antes.
            BigDecimal flow = BigDecimal.ZERO;
            for (; next < operations.size() && !operations.get(next).getDate().isAfter(day); next++) {
                Operation op = operations.get(next);
                BigDecimal qty = op.getQuantity().multiply(splitService.cumulativeFactor(
                        splits.getOrDefault(op.getTicker().toUpperCase(), List.of()), op.getDate(), today));
                held.merge(op.getAssetName(), op.getType().reducesPosition() ? qty.negate() : qty,
                        BigDecimal::add);
                // Precio por título, en las acciones de hoy como la cantidad. Sin importe no hay
                // precio: una compra a coste cero diría que el valor no vale nada.
                if (amount(op).signum() > 0 && qty.signum() > 0) {
                    lastTradePrice.put(op.getAssetName(), amount(op).divide(qty, 6, RoundingMode.HALF_UP));
                }
                flow = flow.add(costChange(op, costBySell));
                if (breakdown) {
                    heldCost.merge(op.getAssetName(), heldCostChange(op, costBySell, costByTransferIn),
                            BigDecimal::add);
                }
            }
            if (breakdown) {
                int day0 = points.size();
                heldCost.forEach((isin, c) ->
                        costByIsin.computeIfAbsent(isin, k -> new BigDecimal[days])[day0] = c);
            }

            BigDecimal value = BigDecimal.ZERO;
            boolean complete = true;
            for (Map.Entry<String, BigDecimal> position : held.entrySet()) {
                if (position.getValue().signum() <= 0) continue;
                String isin = position.getKey();
                String symbol = symbols.computeIfAbsent(isin, k -> symbolOf(historic, userId, k));
                BigDecimal eur = symbol == null ? null : valueOf(historic, symbol, position.getValue(), day);
                if (eur == null && lastTradePrice.containsKey(isin)) {
                    // Sin cierre de Yahoo, el precio de su última operación: es un precio de
                    // mercado de verdad, solo que más viejo. Dejarlo fuera pintaba una pérdida
                    // del tamaño entero de la posición; así, a lo sumo, se queda plano.
                    eur = position.getValue().multiply(lastTradePrice.get(isin));
                    estimated.add(names.getOrDefault(isin, isin));
                    complete = false;
                }
                if (eur == null) {
                    complete = false;
                    missing.add(names.getOrDefault(isin, isin));
                } else {
                    value = value.add(eur);
                    if (breakdown) {
                        byIsin.computeIfAbsent(isin, k -> new BigDecimal[days])[points.size()] = eur;
                    }
                }
            }
            // Sin el dinero que viaja entre fondos, cada traspaso abriría un hueco rojo de su
            // tamaño entero durante los días que tarda en llegar.
            value = value.add(inTransitAt(operations, day).values().stream()
                    .reduce(BigDecimal.ZERO, BigDecimal::add));

            cost = cost.add(flow);
            points.add(new PortfolioHistory.Point(day.toString(), scaled(value), scaled(cost), complete));
        }
        return new PortfolioHistory(points, List.copyOf(estimated), List.copyOf(missing),
                salesBetween(operations, sumBySell(records, SaleRecord::getGainLoss), from, to),
                breakdown ? breakdownOf(byIsin, costByIsin, points, names) : List.of());
    }

    /**
     * Lo que una operación cambia el coste de lo que queda de su valor. Como {@link #costChange},
     * pero mirando cada fondo y no la cartera: ahí un traspaso no movía nada porque el coste salía
     * de un fondo y entraba en otro, y aquí hay que apuntar las dos mitades. La salida se lleva lo
     * que el FIFO apuntó en ella, y la entrada recibe lo que costaron sus lotes.
     *
     * <p>Un canje reparte el coste entre los lotes del mismo valor, así que no cambia su total.
     */
    private static BigDecimal heldCostChange(Operation op, Map<Long, BigDecimal> costBySell,
                                             Map<Long, BigDecimal> costByTransferIn) {
        return switch (op.getType()) {
            case BUY -> amount(op);
            case SELL -> costBySell.getOrDefault(op.getId(), BigDecimal.ZERO).negate();
            case TRASPASO_OUT -> op.getTransferCost() == null ? BigDecimal.ZERO : op.getTransferCost().negate();
            case TRASPASO_IN -> costByTransferIn.getOrDefault(op.getId(), BigDecimal.ZERO);
            default -> BigDecimal.ZERO;
        };
    }

    /** Coste con el que entró cada entrada de traspaso: la suma de los lotes que creó. */
    private Map<Long, BigDecimal> costByTransferIn(Long userId) {
        return fifoLotRepo.findByUserId(userId).stream()
                .filter(lot -> lot.getOperation().getType() == OperationType.TRASPASO_IN)
                .collect(Collectors.groupingBy(lot -> lot.getOperation().getId(),
                        Collectors.reducing(BigDecimal.ZERO, FifoLot::getInitialCost, BigDecimal::add)));
    }

    private List<PortfolioHistory.Holding> breakdownOf(Map<String, BigDecimal[]> byIsin,
                                                       Map<String, BigDecimal[]> costByIsin,
                                                       List<PortfolioHistory.Point> points,
                                                       Map<String, String> names) {
        int lastDay = points.size() - 1;
        Set<String> chosen = new HashSet<>();
        for (int i = 0; i < points.size(); i++) {
            int day = i;
            byIsin.entrySet().stream()
                    .filter(e -> e.getValue()[day] != null && e.getValue()[day].signum() > 0)
                    .sorted(Comparator.comparing((Map.Entry<String, BigDecimal[]> e) -> e.getValue()[day])
                            .reversed())
                    .limit(BREAKDOWN_TOP)
                    .forEach(e -> chosen.add(e.getKey()));
        }
        Function<String, BigDecimal> today = isin -> Optional.ofNullable(byIsin.get(isin)[lastDay])
                .orElse(BigDecimal.ZERO);
        Function<String, BigDecimal> peak = isin -> Arrays.stream(byIsin.get(isin))
                .filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
        // Si no caben todos, se quedan los que más llegaron a valer, no los que más valen hoy: un
        // valor vendido que pesaba miles de euros explica la curva mucho más que uno de cien que
        // sigue en cartera, y mandarlo al resto lo hacía crecer como si fuera calderilla.
        List<String> top = chosen.stream()
                .sorted(Comparator.comparing(peak).reversed().thenComparing(Comparator.naturalOrder()))
                .limit(BREAKDOWN_MAX)
                .sorted(Comparator.comparing(today).thenComparing(peak).reversed()
                        .thenComparing(Comparator.naturalOrder()))
                .toList();

        List<PortfolioHistory.Holding> out = new ArrayList<>();
        BigDecimal[] rest = new BigDecimal[points.size()];
        BigDecimal[] restCost = new BigDecimal[points.size()];
        for (int i = 0; i < rest.length; i++) {
            rest[i] = points.get(i).value();
            restCost[i] = points.get(i).cost();
        }
        BigDecimal[] none = new BigDecimal[points.size()];
        for (String isin : top) {
            List<BigDecimal> values = new ArrayList<>(rest.length);
            List<BigDecimal> costs = new ArrayList<>(rest.length);
            BigDecimal[] daily = byIsin.get(isin);
            BigDecimal[] dailyCost = costByIsin.getOrDefault(isin, none);
            for (int i = 0; i < rest.length; i++) {
                BigDecimal eur = daily[i] == null ? BigDecimal.ZERO : scaled(daily[i]);
                // Lo vendido entero deja céntimos de redondeo en el coste: sin títulos no hay coste.
                BigDecimal c = daily[i] == null || dailyCost[i] == null ? BigDecimal.ZERO : scaled(dailyCost[i]);
                values.add(eur);
                costs.add(c);
                rest[i] = rest[i].subtract(eur);
                restCost[i] = restCost[i].subtract(c);
            }
            out.add(new PortfolioHistory.Holding(names.getOrDefault(isin, isin), isin, values, costs));
        }
        // El resto se queda con lo que falta hasta el coste de la cartera: incluye el de los
        // valores pequeños y el del dinero que viaja entre fondos, que no está en ninguno.
        out.add(new PortfolioHistory.Holding("Resto", null, List.of(rest), List.of(restCost)));
        return out;
    }

    /** Fecha de la operación más antigua del inversor, o vacío si no tiene ninguna. */
    public Optional<LocalDate> firstOperationDate(Long userId) {
        return operationRepo.findByUserId(userId).stream()
                .map(Operation::getDate)
                .min(Comparator.naturalOrder());
    }

    /**
     * Las ventas de la ventana con lo que dejaron, para marcarlas en la gráfica: el día de una
     * venta el beneficio baja de golpe por lo que se realizó, y sin la marca parecería que lo ha
     * hecho la bolsa.
     *
     * <p>El resultado sale de los registros de venta, los mismos que van a la AEAT. La parte de
     * una venta que no casó con ningún lote no tiene registro y por tanto tampoco resultado.
     */
    private List<PortfolioHistory.Sale> salesBetween(List<Operation> operations,
                                                     Map<Long, BigDecimal> gainBySell,
                                                     LocalDate from, LocalDate to) {
        return operations.stream()
                .filter(op -> op.getType() == OperationType.SELL
                        && !op.getDate().isBefore(from) && !op.getDate().isAfter(to))
                .map(op -> new PortfolioHistory.Sale(op.getDate().toString(), op.getTicker(),
                        scaled(amount(op)), scaled(gainBySell.getOrDefault(op.getId(), BigDecimal.ZERO))))
                .toList();
    }

    private static Map<Long, BigDecimal> sumBySell(List<SaleRecord> records,
                                                   Function<SaleRecord, BigDecimal> field) {
        return records.stream().collect(Collectors.groupingBy(r -> r.getSellOperation().getId(),
                Collectors.reducing(BigDecimal.ZERO, field, BigDecimal::add)));
    }

    /**
     * Lo que una operación cambia el coste de la cartera. La compra lo sube por lo que se pagó; la
     * venta lo baja por el coste FIFO de los lotes que consumió, no por lo cobrado: la diferencia
     * es el resultado, que sale del beneficio y no de lo puesto. Un canje solo reparte el coste
     * entre lotes y un traspaso lo cambia de fondo, así que ninguno lo mueve. Salvo la parte de una
     * salida sin lote, que entra con el valor del día porque así da de alta el FIFO el destino (ver
     * {@link MissingOrigin}).
     */
    private static BigDecimal costChange(Operation op, Map<Long, BigDecimal> costBySell) {
        return switch (op.getType()) {
            case BUY -> amount(op);
            case SELL -> costBySell.getOrDefault(op.getId(), BigDecimal.ZERO).negate();
            case TRASPASO_OUT -> MissingOrigin.unmatchedValue(op);
            default -> BigDecimal.ZERO;
        };
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

        // Lo que aquel día estaba viajando de un fondo a otro también era de la cartera, aunque no
        // estuviera en ningún fondo. Sin contarlo, el valor inicial sale corto por ese importe y la
        // variación del periodo se lo apunta como si el mercado lo hubiera regalado.
        Map<String, BigDecimal> inTransit = inTransitAt(operations, at);
        BigDecimal viajando = inTransit.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);

        missing.sort(Comparator.naturalOrder());
        return new PeriodBaseline(period, at.toString(), scaled(value.add(viajando)),
                scaled(sumAfter(operations, at, OperationType.BUY)
                        .add(unmatchedAfter(operations, at))),
                scaled(sumAfter(operations, at, OperationType.SELL)),
                missing,
                positions(operations, splits, at, today, opening, heldAt, inTransit),
                // La parte en tránsito de una entrada no es una llegada nueva: ya estaba contada
                // en el valor inicial, así que sale del desajuste igual que entró en el valor.
                scaled(sumAfter(operations, at, OperationType.TRASPASO_IN)
                        .subtract(viajando)
                        .subtract(sumAfter(operations, at, OperationType.TRASPASO_OUT))));
    }

    /**
     * Dinero que en {@code at} había salido de un fondo y no había llegado al siguiente, por el
     * valor donde acabó aterrizando.
     *
     * <p>Un traspaso entre fondos tarda días en ejecutarse, y en ese hueco el dinero no está en
     * ninguna posición: los libros no tienen cuenta de efectivo, así que desaparece de la cartera
     * y reaparece al llegar. Si una ventana empieza justo en ese hueco, el valor inicial no lo
     * cuenta, el valor de hoy sí, y como un traspaso no es dinero nuevo tampoco se resta por
     * ningún lado: el periodo entero se lleva ese importe como si fuera revalorización.
     *
     * <p>Se empareja por {@code transferId}, que es lo que une las dos patas. Por tanda de
     * traspaso, lo que ya había salido menos lo que ya había llegado es lo que estaba en el aire,
     * y se reparte entre las entradas posteriores por orden de fecha: son las que dicen en qué
     * fondo aterrizó.
     */
    static Map<String, BigDecimal> inTransitAt(List<Operation> operations, LocalDate at) {
        Map<String, List<Operation>> byTransfer = operations.stream()
                .filter(op -> op.getType().isTransfer() && op.getTransferId() != null)
                .collect(Collectors.groupingBy(Operation::getTransferId, LinkedHashMap::new,
                        Collectors.toList()));

        Map<String, BigDecimal> landing = new LinkedHashMap<>();
        byTransfer.values().forEach(legs -> {
            BigDecimal enElAire = legs.stream()
                    .filter(op -> !op.getDate().isAfter(at))
                    .map(op -> op.getType() == OperationType.TRASPASO_OUT
                            ? amount(op) : amount(op).negate())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (enElAire.signum() <= 0) return;

            for (Operation in : legs.stream()
                    .filter(op -> op.getType() == OperationType.TRASPASO_IN && op.getDate().isAfter(at))
                    .sorted(Comparator.comparing(Operation::getDate).thenComparing(Operation::getId,
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .toList()) {
                if (enElAire.signum() <= 0) break;
                BigDecimal parte = amount(in).min(enElAire);
                landing.merge(in.getAssetName(), parte, BigDecimal::add);
                enElAire = enElAire.subtract(parte);
            }
        });
        return landing;
    }

    private static BigDecimal amount(Operation op) {
        return op.getTotal() == null ? BigDecimal.ZERO : op.getTotal();
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
                                           Map<String, BigDecimal> heldAt,
                                           Map<String, BigDecimal> inTransit) {
        Map<String, PeriodFlow> flows = flowsAfter(operations, splits, at, today);
        // El nombre sale de todas las operaciones del valor y no solo de las del periodo: en una
        // semana casi ninguna posición tiene movimientos, y sin esto el desglose enseñaba el ISIN
        // en la columna del nombre.
        Map<String, String> names = tickersByIsin(operations);
        Map<String, PeriodPosition> out = new LinkedHashMap<>();

        opening.forEach((isin, eur) -> out.put(isin, position(isin, names.getOrDefault(isin, isin), eur,
                soldShareOf(eur, heldAt.get(isin),
                        flows.containsKey(isin) ? flows.get(isin).outQty() : BigDecimal.ZERO),
                flows.get(isin), inTransit.getOrDefault(isin, BigDecimal.ZERO))));
        // Los que no se tenían aquel día pero recibieron dinero después: una compra nueva, o el
        // fondo de destino de un traspaso. Su valor inicial es cero, no "falta el dato" —salvo el
        // dinero que ya venía de camino hacia ellos, que sí era suyo aquel día.
        flows.forEach((isin, flow) -> out.computeIfAbsent(isin, k ->
                position(isin, names.getOrDefault(isin, flow.ticker()),
                        BigDecimal.ZERO, BigDecimal.ZERO, flow,
                        inTransit.getOrDefault(isin, BigDecimal.ZERO))));
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

    /**
     * El dinero que venía de camino se apunta en el valor inicial del fondo donde aterrizó, y deja
     * de contar como entrada: no era dinero llegando, era dinero que ya era suyo. La contribución
     * del valor no cambia —se resta en un sitio en vez de otro— pero la de la cartera sí, que es
     * donde estaba el error.
     */
    private PeriodPosition position(String isin, String ticker, BigDecimal opening,
                                    BigDecimal openingSold, PeriodFlow flow, BigDecimal inTransit) {
        BigDecimal inflow = (flow == null ? BigDecimal.ZERO : flow.inflow()).subtract(inTransit);
        return new PeriodPosition(isin, ticker, scaled(opening.add(inTransit)), scaled(openingSold),
                scaled(inflow),
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
