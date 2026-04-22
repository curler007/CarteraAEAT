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
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FifoService {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final int SCALE = 8;

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
     * Procesa una venta aplicando FIFO global por ticker (independientemente del broker).
     * Crea un SaleRecord por cada lote consumido.
     *
     * @return lista de SaleRecords generados
     */
    @Transactional
    public List<SaleRecord> processSell(Operation sellOp) {
        String ticker = sellOp.getTicker().toUpperCase();
        BigDecimal qtyToSell = sellOp.getQuantity();
        BigDecimal totalProceeds = sellOp.getTotal(); // ya neto de comisión

        // Si hay alguna venta pendiente anterior, esta también queda pendiente completa:
        // no podemos saltarnos el orden FIFO entre ventas
        boolean blockedByPriorPendingSell = operationRepo
                .existsByTickerAndTypeAndPendingQtyGreaterThanAndDateBefore(
                        ticker, OperationType.SELL, ZERO, sellOp.getDate());

        if (blockedByPriorPendingSell) {
            sellOp.setPendingQty(qtyToSell);
            operationRepo.save(sellOp);
            return java.util.List.of();
        }

        // Solo lotes comprados en fecha <= fecha de venta (FIFO correcto)
        List<FifoLot> lots = fifoLotRepo
                .findByTickerAndRemainingQtyGreaterThanAndPurchaseDateLessThanEqualOrderByPurchaseDateAscIdAsc(
                        ticker, ZERO, sellOp.getDate());

        BigDecimal totalAvailable = lots.stream()
                .map(FifoLot::getRemainingQty)
                .reduce(ZERO, BigDecimal::add);

        // Cantidad que podemos casar ahora; el resto queda pendiente
        BigDecimal qtyCanMatch = qtyToSell.min(totalAvailable);
        BigDecimal qtyPending  = qtyToSell.subtract(qtyCanMatch);

        // Actualizar pendingQty en la operación
        sellOp.setPendingQty(qtyPending.compareTo(ZERO) == 0 ? BigDecimal.ZERO : qtyPending);
        operationRepo.save(sellOp);

        BigDecimal qtyRemaining = qtyCanMatch;
        List<SaleRecord> records = new java.util.ArrayList<>();

        for (FifoLot lot : lots) {
            if (qtyRemaining.compareTo(ZERO) == 0) break;

            BigDecimal consumed = qtyRemaining.min(lot.getRemainingQty());

            // Proporción del coste de este lote que se imputa
            BigDecimal costProportion = consumed
                    .divide(lot.getRemainingQty(), SCALE, RoundingMode.HALF_UP)
                    .multiply(lot.getRemainingCost())
                    .setScale(6, RoundingMode.HALF_UP);

            // Proporción de los ingresos de la venta asignada a esta parte
            BigDecimal proceedsProportion = consumed
                    .divide(qtyToSell, SCALE, RoundingMode.HALF_UP)
                    .multiply(totalProceeds)
                    .setScale(6, RoundingMode.HALF_UP);

            // Actualizar el lote
            lot.setRemainingQty(lot.getRemainingQty().subtract(consumed));
            lot.setRemainingCost(lot.getRemainingCost().subtract(costProportion));
            fifoLotRepo.save(lot);

            // Crear el SaleRecord
            SaleRecord sr = new SaleRecord();
            sr.setSellOperation(sellOp);
            sr.setConsumedLot(lot);
            sr.setTicker(ticker);
            sr.setAssetName(sellOp.getAssetName());
            sr.setPurchaseDate(lot.getPurchaseDate());
            sr.setBuyBroker(lot.getBroker());
            sr.setSaleDate(sellOp.getDate());
            sr.setSellBroker(sellOp.getBroker());
            sr.setQuantity(consumed);
            sr.setCostBasis(costProportion);
            sr.setProceeds(proceedsProportion);
            sr.setGainLoss(proceedsProportion.subtract(costProportion));
            sr.setAeatGroup(sellOp.getAeatGroup());
            sr.setTaxYear(sellOp.getDate().getYear());
            records.add(saleRecordRepo.save(sr));

            qtyRemaining = qtyRemaining.subtract(consumed);
        }

        return records;
    }

    /**
     * Redistribuye el coste de los lotes existentes hacia el lote de canje,
     * de forma que el coste total no varía pero se reparte proporcionalmente
     * entre todas las acciones (anteriores + nuevas). LIRPF Art. 37.1.a
     */
    @Transactional
    public void processCanje(Operation canjeOp) {
        String ticker = canjeOp.getTicker().toUpperCase();
        BigDecimal canjeQty = canjeOp.getQuantity();

        FifoLot canjeLot = fifoLotRepo.findByOperation_Id(canjeOp.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "Lote CANJE no encontrado para operación " + canjeOp.getId()));

        // Lotes existentes (excluido el propio lote de canje) con qty > 0 y fecha <= canje
        List<FifoLot> existingLots = fifoLotRepo
                .findByTickerAndRemainingQtyGreaterThanAndPurchaseDateLessThanEqualOrderByPurchaseDateAscIdAsc(
                        ticker, ZERO, canjeOp.getDate())
                .stream()
                .filter(l -> !l.getId().equals(canjeLot.getId()))
                .collect(Collectors.toList());

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

    /**
     * Recalcula el FIFO completo para un ticker dado.
     * Se invoca cuando se detecta una inserción desordenada, al eliminar operaciones
     * o al guardar/eliminar un split.
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
    public void recalculateFifo(String ticker) {
        // 1. Borrar SaleRecords
        saleRecordRepo.deleteByTicker(ticker);

        // 2. Resetear FifoLots (BUY → coste original; CANJE → 0)
        fifoLotRepo.findByTickerOrderByPurchaseDateAscIdAsc(ticker).forEach(lot -> {
            lot.setRemainingQty(lot.getInitialQty());
            lot.setRemainingCost(lot.getInitialCost());
            fifoLotRepo.save(lot);
        });

        // 3. Reprocesar en orden cronológico, splits antes que operaciones del mismo día
        List<Split> splits = splitRepo.findByTickerOrderByDateAscIdAsc(ticker);
        List<Operation> ops = operationRepo.findByTickerOrderByDateAscIdAsc(ticker);
        int si = 0, oi = 0;

        while (si < splits.size() || oi < ops.size()) {
            boolean takeSplit = si < splits.size() && (oi >= ops.size()
                    || !splits.get(si).getDate().isAfter(ops.get(oi).getDate()));

            if (takeSplit) {
                applySplitToOpenLots(splits.get(si++));
            } else {
                Operation op = ops.get(oi++);
                if (op.getType() == OperationType.CANJE) {
                    processCanje(op);
                } else if (op.getType() == OperationType.SELL) {
                    op.setPendingQty(BigDecimal.ZERO);
                    operationRepo.save(op);
                    processSell(op);
                }
                // BUY: lote ya reseteado, sin acción adicional
            }
        }
    }

    /**
     * Multiplica la cantidad restante de todos los lotes abiertos del ticker
     * por el ratio del split. El coste no varía.
     */
    private void applySplitToOpenLots(Split split) {
        fifoLotRepo.findByTickerOrderByPurchaseDateAscIdAsc(split.getTicker()).stream()
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
    public void reverseSell(Long sellOperationId) {
        List<SaleRecord> records = saleRecordRepo.findBySellOperation_Id(sellOperationId);
        for (SaleRecord sr : records) {
            FifoLot lot = sr.getConsumedLot();
            lot.setRemainingQty(lot.getRemainingQty().add(sr.getQuantity()));
            lot.setRemainingCost(lot.getRemainingCost().add(sr.getCostBasis()));
            fifoLotRepo.save(lot);
            saleRecordRepo.delete(sr);
        }
    }
}
