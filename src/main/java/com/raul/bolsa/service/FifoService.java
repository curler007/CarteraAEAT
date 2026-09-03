package com.raul.bolsa.service;

import com.raul.bolsa.domain.FifoLot;
import com.raul.bolsa.domain.Operation;
import com.raul.bolsa.domain.OperationType;
import com.raul.bolsa.domain.SaleRecord;
import com.raul.bolsa.domain.Split;
import com.raul.bolsa.repository.FifoLotRepository;
import com.raul.bolsa.repository.OperationRepository;
import com.raul.bolsa.repository.SaleRecordRepository;
import com.raul.bolsa.repository.SplitRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * El FIFO se aplica globalmente por ticker dentro de un mismo usuario: nunca entre usuarios.
 * Donde el método ya recibe una entidad, el propietario se deriva de ella
 * ({@code sellOp.getUserId()}) para que no pueda desalinearse.
 */
@Service
@RequiredArgsConstructor
public class FifoService {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final int SCALE = 8;

    /** Tipos que sacan titulos de la cartera consumiendo lotes por FIFO. */
    private static final List<OperationType> DISPOSALS =
            List.of(OperationType.SELL, OperationType.TRASPASO_OUT);

    /** Las dos patas de un traspaso, que obligan a recalcular la cartera entera. */
    private static final List<OperationType> TRANSFERS =
            List.of(OperationType.TRASPASO_OUT, OperationType.TRASPASO_IN);

    private final FifoLotRepository fifoLotRepo;
    private final SaleRecordRepository saleRecordRepo;
    private final OperationRepository operationRepo;
    private final SplitRepository splitRepo;

    /**
     * Registra un lote de compra.
     */
    @Transactional
    public FifoLot createLot(Operation buyOp) {
        FifoLot lot = new FifoLot();
        lot.setUserId(requireOwner(buyOp));
        lot.setOperation(buyOp);
        lot.setTicker(buyOp.getTicker().toUpperCase());
        lot.setAssetName(buyOp.getAssetName());
        lot.setPurchaseDate(buyOp.getDate());
        lot.setBroker(buyOp.getBroker());
        lot.setInitialQty(buyOp.getQuantity());
        lot.setRemainingQty(buyOp.getQuantity());
        lot.setInitialCost(buyOp.getTotal());
        lot.setRemainingCost(buyOp.getTotal());
        return fifoLotRepo.save(lot);
    }

    /**
     * Procesa una venta aplicando FIFO global por ticker (independientemente del broker)
     * dentro de la cartera del propietario de la operación.
     * Crea un SaleRecord por cada lote consumido.
     *
     * @return lista de SaleRecords generados
     */
    @Transactional
    public List<SaleRecord> processSell(Operation sellOp) {
        Long userId = requireOwner(sellOp);
        String ticker = sellOp.getTicker().toUpperCase();
        BigDecimal totalProceeds = sellOp.getTotal(); // ya neto de comisión

        List<Consumed> consumed = consumeLots(sellOp);
        if (consumed.isEmpty()) return List.of();

        List<SaleRecord> records = new ArrayList<>();
        for (Consumed c : consumed) {
            // Proporción de los ingresos de la venta asignada a esta parte
            BigDecimal proceedsProportion = c.qty()
                    .divide(sellOp.getQuantity(), SCALE, RoundingMode.HALF_UP)
                    .multiply(totalProceeds)
                    .setScale(6, RoundingMode.HALF_UP);

            SaleRecord sr = new SaleRecord();
            sr.setUserId(userId);
            sr.setSellOperation(sellOp);
            sr.setConsumedLot(c.lot());
            sr.setTicker(ticker);
            sr.setAssetName(sellOp.getAssetName());
            sr.setPurchaseDate(c.lot().getPurchaseDate());
            sr.setBuyBroker(c.lot().getBroker());
            sr.setSaleDate(sellOp.getDate());
            sr.setSellBroker(sellOp.getBroker());
            sr.setQuantity(c.qty());
            sr.setCostBasis(c.cost());
            sr.setProceeds(proceedsProportion);
            sr.setGainLoss(proceedsProportion.subtract(c.cost()));
            sr.setAeatGroup(sellOp.getAeatGroup());
            sr.setTaxYear(sellOp.getDate().getYear());
            records.add(saleRecordRepo.save(sr));
        }
        return records;
    }

    /** Trozo de un lote consumido por una venta o por la salida de un traspaso. */
    private record Consumed(FifoLot lot, BigDecimal qty, BigDecimal cost) {}

    /**
     * Consume por FIFO los lotes que hacen falta para cubrir la operación y marca en ella la
     * cantidad que no ha podido casarse. Común a la venta y a la salida de un traspaso: las dos
     * sacan títulos de la cartera y solo se diferencian en qué hacen con el coste liberado.
     */
    private List<Consumed> consumeLots(Operation op) {
        Long userId = requireOwner(op);
        String ticker = op.getTicker().toUpperCase();
        BigDecimal qtyToSell = op.getQuantity();

        // Si hay alguna salida pendiente anterior, esta también queda pendiente completa:
        // no podemos saltarnos el orden FIFO entre ventas
        boolean blockedByPriorPending = operationRepo
                .existsByUserIdAndTickerAndTypeInAndPendingQtyGreaterThanAndDateBefore(
                        userId, ticker, DISPOSALS, ZERO, op.getDate());

        if (blockedByPriorPending) {
            op.setPendingQty(qtyToSell);
            operationRepo.save(op);
            return List.of();
        }

        // Solo lotes propios comprados en fecha <= fecha de venta (FIFO correcto)
        List<FifoLot> lots = fifoLotRepo
                .findByUserIdAndTickerAndRemainingQtyGreaterThanAndPurchaseDateLessThanEqualOrderByPurchaseDateAscIdAsc(
                        userId, ticker, ZERO, op.getDate());

        BigDecimal totalAvailable = lots.stream()
                .map(FifoLot::getRemainingQty)
                .reduce(ZERO, BigDecimal::add);

        // Cantidad que podemos casar ahora; el resto queda pendiente
        BigDecimal qtyCanMatch = qtyToSell.min(totalAvailable);
        BigDecimal qtyPending  = qtyToSell.subtract(qtyCanMatch);

        op.setPendingQty(qtyPending.compareTo(ZERO) == 0 ? BigDecimal.ZERO : qtyPending);
        operationRepo.save(op);

        BigDecimal qtyRemaining = qtyCanMatch;
        List<Consumed> consumed = new ArrayList<>();

        for (FifoLot lot : lots) {
            if (qtyRemaining.compareTo(ZERO) == 0) break;

            BigDecimal qty = qtyRemaining.min(lot.getRemainingQty());

            // Proporción del coste de este lote que se imputa
            BigDecimal costProportion = qty
                    .divide(lot.getRemainingQty(), SCALE, RoundingMode.HALF_UP)
                    .multiply(lot.getRemainingCost())
                    .setScale(6, RoundingMode.HALF_UP);

            lot.setRemainingQty(lot.getRemainingQty().subtract(qty));
            lot.setRemainingCost(lot.getRemainingCost().subtract(costProportion));
            fifoLotRepo.save(lot);

            consumed.add(new Consumed(lot, qty, costProportion));
            qtyRemaining = qtyRemaining.subtract(qty);
        }
        return consumed;
    }

    /**
     * Redistribuye el coste de los lotes existentes hacia el lote de canje,
     * de forma que el coste total no varía pero se reparte proporcionalmente
     * entre todas las acciones (anteriores + nuevas). LIRPF Art. 37.1.a
     */
    @Transactional
    public void processCanje(Operation canjeOp) {
        Long userId = requireOwner(canjeOp);
        String ticker = canjeOp.getTicker().toUpperCase();
        BigDecimal canjeQty = canjeOp.getQuantity();

        FifoLot canjeLot = fifoLotRepo.findByOperation_Id(canjeOp.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "Lote CANJE no encontrado para operación " + canjeOp.getId()));

        // Lotes existentes (excluido el propio lote de canje) con qty > 0 y fecha <= canje
        List<FifoLot> existingLots = fifoLotRepo
                .findByUserIdAndTickerAndRemainingQtyGreaterThanAndPurchaseDateLessThanEqualOrderByPurchaseDateAscIdAsc(
                        userId, ticker, ZERO, canjeOp.getDate())
                .stream()
                .filter(l -> !l.getId().equals(canjeLot.getId()))
                .toList();

        BigDecimal existingQty = existingLots.stream()
                .map(FifoLot::getRemainingQty).reduce(ZERO, BigDecimal::add);
        BigDecimal existingCost = existingLots.stream()
                .map(FifoLot::getRemainingCost).reduce(ZERO, BigDecimal::add);

        if (existingQty.compareTo(ZERO) == 0 || existingCost.compareTo(ZERO) == 0) {
            return; // sin coste que redistribuir
        }

        BigDecimal totalQty = existingQty.add(canjeQty);
        BigDecimal totalTransferred = ZERO;

        for (FifoLot lot : existingLots) {
            BigDecimal transfer = lot.getRemainingCost()
                    .multiply(canjeQty)
                    .divide(totalQty, SCALE, RoundingMode.HALF_UP);
            lot.setRemainingCost(lot.getRemainingCost().subtract(transfer));
            fifoLotRepo.save(lot);
            totalTransferred = totalTransferred.add(transfer);
        }

        canjeLot.setRemainingCost(totalTransferred);
        fifoLotRepo.save(canjeLot);
    }

    // ─── Traspasos entre fondos ──────────────────────────────────────────────

    /**
     * Coste que sale de un fondo en un traspaso. Arrastra la fecha de adquisición original, que
     * es lo que hace que el traspaso sea neutro: en el fondo de destino las participaciones
     * siguen siendo tan antiguas como lo eran en el de origen.
     *
     * @param marketValue valor de mercado de ese trozo en el momento del traspaso, que es lo que
     *                    determina cuántas participaciones compra en el destino
     */
    private record CostFragment(LocalDate purchaseDate, String broker,
                                BigDecimal cost, BigDecimal marketValue) {}

    /**
     * Salida de un traspaso: consume lotes por FIFO como una venta, pero el coste liberado no se
     * imputa como ganancia, sino que se guarda para volcarlo en los fondos de destino. No genera
     * ningún SaleRecord, y por eso no llega a la declaración: un traspaso entre fondos no tributa
     * (LIRPF Art. 94).
     */
    private void processTransferOut(Operation op, List<CostFragment> pot) {
        // Valor de mercado por título al salir: en el destino el coste se reparte en proporción
        // a lo que vale cada trozo, no a lo que costó en su día.
        BigDecimal valuePerUnit = op.getQuantity().signum() == 0
                ? ZERO
                : op.getTotal().divide(op.getQuantity(), SCALE, RoundingMode.HALF_UP);

        for (Consumed c : consumeLots(op)) {
            pot.add(new CostFragment(
                    c.lot().getPurchaseDate(),
                    c.lot().getBroker(),
                    c.cost(),
                    c.qty().multiply(valuePerUnit).setScale(6, RoundingMode.HALF_UP)));
        }
    }

    /**
     * Entrada de un traspaso: crea los lotes del fondo de destino heredando fecha y coste del
     * origen. Sale un lote por cada fecha de adquisición distinta que venga en el bote, que es el
     * detalle que el FIFO necesita; los trozos que comparten fecha se funden, porque el FIFO los
     * consumiría a la vez de todas formas.
     *
     * @param share fracción del bote que corresponde a esta entrada, según su peso en el evento
     */
    private void processTransferIn(Operation op, List<CostFragment> pot, BigDecimal share) {
        Long userId = requireOwner(op);

        Map<LocalDate, CostFragment> merged = new LinkedHashMap<>();
        for (CostFragment f : pot) {
            merged.merge(f.purchaseDate(), f, (a, b) -> new CostFragment(
                    a.purchaseDate(), a.broker(),
                    a.cost().add(b.cost()), a.marketValue().add(b.marketValue())));
        }
        List<CostFragment> fragments = merged.values().stream()
                .sorted(Comparator.comparing(CostFragment::purchaseDate))
                .toList();

        BigDecimal totalValue = fragments.stream()
                .map(CostFragment::marketValue).reduce(ZERO, BigDecimal::add);

        if (fragments.isEmpty() || totalValue.signum() == 0) {
            // El origen del traspaso ha quedado fuera de lo importado: sin nada que heredar, lo
            // más parecido a la realidad es tratarlo como una compra por el valor que entró.
            createTransferLot(userId, op, op.getDate(), op.getBroker(),
                    op.getQuantity(), op.getTotal());
            return;
        }

        // El último lote se lleva el resto, para que los títulos sumen exactamente los suscritos.
        BigDecimal qtyLeft = op.getQuantity();
        for (int i = 0; i < fragments.size(); i++) {
            CostFragment f = fragments.get(i);
            boolean last = i == fragments.size() - 1;
            BigDecimal qty = last ? qtyLeft : op.getQuantity()
                    .multiply(f.marketValue())
                    .divide(totalValue, SCALE, RoundingMode.HALF_UP)
                    .min(qtyLeft);
            if (qty.signum() <= 0) continue;
            createTransferLot(userId, op, f.purchaseDate(), f.broker(), qty,
                    f.cost().multiply(share).setScale(6, RoundingMode.HALF_UP));
            qtyLeft = qtyLeft.subtract(qty);
        }
    }

    private void createTransferLot(Long userId, Operation op, LocalDate purchaseDate,
                                   String broker, BigDecimal qty, BigDecimal cost) {
        FifoLot lot = new FifoLot();
        lot.setUserId(userId);
        lot.setOperation(op);
        lot.setTicker(op.getTicker().toUpperCase());
        lot.setAssetName(op.getAssetName());
        lot.setPurchaseDate(purchaseDate);
        lot.setBroker(broker);
        lot.setInitialQty(qty);
        lot.setRemainingQty(qty);
        lot.setInitialCost(cost);
        lot.setRemainingCost(cost);
        fifoLotRepo.save(lot);
    }

    /**
     * Reproduce un traspaso entero: primero todas las salidas, que llenan el bote de coste, y
     * después todas las entradas, que se lo reparten en proporción a lo que recibió cada fondo.
     *
     * <p>Se procesa en bloque porque el reparto es de varios fondos a varios fondos y las órdenes
     * se ejecutan en días distintos: mirando cada pata por separado no habría forma de saber qué
     * fracción del coste le toca a cada destino.
     */
    private void processTransferEvent(List<Operation> event) {
        List<CostFragment> pot = new ArrayList<>();

        event.stream()
                .filter(op -> op.getType() == OperationType.TRASPASO_OUT)
                .forEach(op -> {
                    op.setPendingQty(ZERO);
                    processTransferOut(op, pot);
                });

        List<Operation> incoming = event.stream()
                .filter(op -> op.getType() == OperationType.TRASPASO_IN)
                .toList();
        BigDecimal totalIn = incoming.stream()
                .map(Operation::getTotal).reduce(ZERO, BigDecimal::add);

        for (Operation op : incoming) {
            BigDecimal share = totalIn.signum() == 0
                    ? BigDecimal.ONE.divide(
                            BigDecimal.valueOf(incoming.size()), SCALE, RoundingMode.HALF_UP)
                    : op.getTotal().divide(totalIn, SCALE, RoundingMode.HALF_UP);
            processTransferIn(op, pot, share);
        }
    }

    /** Traspaso al que pertenece una pata; las que van sueltas forman cada una su propio evento. */
    private static String transferKey(Operation op) {
        return op.getTransferId() != null ? op.getTransferId() : "op:" + op.getId();
    }

    // ─── Recálculo ───────────────────────────────────────────────────────────

    /**
     * Recalcula el FIFO completo de un ticker para un usuario.
     * Se invoca cuando se detecta una inserción desordenada, al eliminar operaciones
     * o al guardar/eliminar un split.
     *
     * <p>En cuanto la cartera tiene traspasos deja de poder hacerse valor a valor y se recalcula
     * entera: el coste de un fondo de destino sale de los lotes del de origen, así que el
     * resultado de un ticker depende del estado de otros.
     *
     * Algoritmo:
     *   1. Eliminar todos los SaleRecords del ticker.
     *   2. Resetear todos los FifoLots a su estado inicial (CANJE lots quedan a coste 0).
     *   3. Reprocesar en orden cronológico mezclando operaciones y splits:
     *      - Splits con fecha <= fecha de la siguiente operación se aplican primero.
     *      - CANJE → processCanje (redistribuye coste)
     *      - SELL  → processSell (consume lotes FIFO)
     *      - BUY   → el lote ya existe reseteado, no hace falta acción
     *      - SPLIT → multiplica remainingQty de todos los lotes abiertos por el ratio
     */
    @Transactional
    public void recalculateFifo(Long userId, String ticker) {
        if (operationRepo.existsByUserIdAndTypeIn(userId, TRANSFERS)) {
            recalculateAll(userId);
            return;
        }

        // 1. Borrar SaleRecords
        saleRecordRepo.deleteByUserIdAndTicker(userId, ticker);

        // 2. Resetear FifoLots (BUY → coste original; CANJE → 0)
        fifoLotRepo.findByUserIdAndTickerOrderByPurchaseDateAscIdAsc(userId, ticker).forEach(lot -> {
            lot.setRemainingQty(lot.getInitialQty());
            lot.setRemainingCost(lot.getInitialCost());
            fifoLotRepo.save(lot);
        });

        // 3. Reprocesar en orden cronológico, splits antes que operaciones del mismo día
        replay(operationRepo.findByUserIdAndTickerOrderByDateAscIdAsc(userId, ticker),
               splitRepo.findByUserIdAndTickerOrderByDateAscIdAsc(userId, ticker));
    }

    /**
     * Recalcula el FIFO de toda la cartera del usuario de una sola pasada, en orden cronológico
     * global. Es lo que exigen los traspasos entre fondos, que atan el coste de un valor al de
     * otro y no se pueden reproducir mirando un ticker aislado.
     */
    @Transactional
    public void recalculateAll(Long userId) {
        saleRecordRepo.deleteByUserId(userId);
        saleRecordRepo.flush();

        // Los lotes de un traspaso no se pueden resetear como los demás: su coste no está en la
        // operación, se deduce del origen, así que hay que rehacerlos desde cero.
        for (FifoLot lot : fifoLotRepo.findByUserIdOrderByPurchaseDateAscIdAsc(userId)) {
            if (lot.getOperation().getType() == OperationType.TRASPASO_IN) {
                fifoLotRepo.delete(lot);
            } else {
                lot.setRemainingQty(lot.getInitialQty());
                lot.setRemainingCost(lot.getInitialCost());
                fifoLotRepo.save(lot);
            }
        }
        fifoLotRepo.flush();

        List<Split> splits = new ArrayList<>(splitRepo.findByUserId(userId));
        splits.sort(Comparator.comparing(Split::getDate).thenComparing(Split::getId));
        replay(operationRepo.findByUserIdOrderByDateAscIdAsc(userId), splits);
    }

    /**
     * Reproduce operaciones y splits en orden cronológico, aplicando los splits del día antes que
     * las operaciones. Los traspasos se reproducen enteros al llegar a su primera pata, porque el
     * coste solo cuadra mirando el evento completo.
     */
    private void replay(List<Operation> ops, List<Split> splits) {
        Map<String, List<Operation>> transfers = new LinkedHashMap<>();
        for (Operation op : ops) {
            if (op.getType().isTransfer()) {
                transfers.computeIfAbsent(transferKey(op), k -> new ArrayList<>()).add(op);
            }
        }
        Set<String> replayed = new HashSet<>();
        int si = 0, oi = 0;

        while (si < splits.size() || oi < ops.size()) {
            boolean takeSplit = si < splits.size() && (oi >= ops.size()
                    || !splits.get(si).getDate().isAfter(ops.get(oi).getDate()));

            if (takeSplit) {
                applySplitToOpenLots(splits.get(si++));
                continue;
            }

            Operation op = ops.get(oi++);
            switch (op.getType()) {
                case CANJE -> processCanje(op);
                case SELL -> {
                    op.setPendingQty(BigDecimal.ZERO);
                    operationRepo.save(op);
                    processSell(op);
                }
                case TRASPASO_OUT, TRASPASO_IN -> {
                    String key = transferKey(op);
                    if (replayed.add(key)) {
                        processTransferEvent(transfers.get(key));
                    }
                }
                // BUY: lote ya reseteado, sin acción adicional
                default -> { }
            }
        }
    }

    /**
     * Multiplica la cantidad restante de todos los lotes abiertos del ticker
     * por el ratio del split. El coste no varía.
     */
    private void applySplitToOpenLots(Split split) {
        fifoLotRepo.findByUserIdAndTickerOrderByPurchaseDateAscIdAsc(
                        split.getUserId(), split.getTicker()).stream()
                .filter(lot -> lot.getRemainingQty().compareTo(ZERO) > 0)
                .forEach(lot -> {
                    lot.setRemainingQty(lot.getRemainingQty()
                            .multiply(split.getRatio())
                            .setScale(SCALE, RoundingMode.HALF_UP));
                    fifoLotRepo.save(lot);
                });
    }

    /**
     * Revierte los SaleRecords de una venta y restaura los lotes consumidos.
     */
    @Transactional
    public void reverseSell(Long userId, Long sellOperationId) {
        List<SaleRecord> records = saleRecordRepo.findByUserIdAndSellOperation_Id(userId, sellOperationId);
        for (SaleRecord sr : records) {
            FifoLot lot = sr.getConsumedLot();
            lot.setRemainingQty(lot.getRemainingQty().add(sr.getQuantity()));
            lot.setRemainingCost(lot.getRemainingCost().add(sr.getCostBasis()));
            fifoLotRepo.save(lot);
            saleRecordRepo.delete(sr);
        }
    }

    /** Ninguna fila puede quedar sin propietario: sería invisible y rompería el FIFO. */
    private static Long requireOwner(Operation op) {
        Long userId = op.getUserId();
        if (userId == null) {
            throw new IllegalStateException(
                    "La operación " + op.getId() + " no tiene propietario asignado");
        }
        return userId;
    }
}
