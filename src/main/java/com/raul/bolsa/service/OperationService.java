package com.raul.bolsa.service;

import com.raul.bolsa.domain.Operation;
import com.raul.bolsa.domain.OperationType;
import com.raul.bolsa.repository.FifoLotRepository;
import com.raul.bolsa.repository.OperationRepository;
import com.raul.bolsa.repository.SaleRecordRepository;
import com.raul.bolsa.web.dto.OperationForm;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
@RequiredArgsConstructor
public class OperationService {

    private final OperationRepository operationRepo;
    private final FifoLotRepository fifoLotRepo;
    private final SaleRecordRepository saleRecordRepo;
    private final FifoService fifoService;

    @Transactional
    public Operation save(OperationForm form) {
        Operation op = buildOperation(form);

        // Recalcular si: hay ventas con SaleRecords de fecha >= nueva op, O hay ventas pendientes
        boolean needsRecalc = saleRecordRepo.existsByTickerAndSaleDateGreaterThanEqual(
                op.getTicker(), op.getDate())
                || operationRepo.existsByTickerAndTypeAndPendingQtyGreaterThan(
                        op.getTicker(), OperationType.SELL, BigDecimal.ZERO);

        if (op.getType() != OperationType.SELL) {
            operationRepo.save(op);
            fifoService.createLot(op);
            if (needsRecalc) {
                fifoService.recalculateFifo(op.getTicker());
            } else if (op.getType() == OperationType.CANJE) {
                fifoService.processCanje(op);
            }
        } else {
            operationRepo.save(op);
            if (needsRecalc) {
                fifoService.recalculateFifo(op.getTicker());
            } else {
                fifoService.processSell(op);
            }
        }

        return op;
    }

    /**
     * Edita una operación: resetea el FIFO del ticker, elimina la operación antigua
     * y la re-inserta con los nuevos datos. El recálculo garantiza la consistencia
     * independientemente de fechas o cambios de tipo.
     */
    @Transactional
    public Operation update(Long id, OperationForm form) {
        Operation existing = operationRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Operación no encontrada: " + id));
        String oldTicker = existing.getTicker();
        String newTicker = form.getTicker().trim().toUpperCase();

        // 1. Resetear FIFO del ticker antiguo: borrar SaleRecords y restaurar lots
        saleRecordRepo.deleteByTicker(oldTicker);
        fifoLotRepo.findByTickerOrderByPurchaseDateAscIdAsc(oldTicker).forEach(lot -> {
            lot.setRemainingQty(lot.getInitialQty());
            lot.setRemainingCost(lot.getInitialCost());
            fifoLotRepo.save(lot);
        });

        // 2. Eliminar el lot de esta operación si era compra o canje
        if (existing.getType() != OperationType.SELL) {
            fifoLotRepo.findByTickerOrderByPurchaseDateAscIdAsc(oldTicker).stream()
                    .filter(l -> l.getOperation().getId().equals(id))
                    .forEach(fifoLotRepo::delete);
        }

        // 3. Eliminar la operación antigua
        operationRepo.delete(existing);

        // 4. Guardar la nueva operación y crear su lot si es compra o canje
        Operation op = buildOperation(form);
        operationRepo.save(op);
        if (op.getType() != OperationType.SELL) {
            fifoService.createLot(op);
        }

        // 5. Recalcular FIFO para el ticker nuevo (reprocesa todas las ventas en orden)
        fifoService.recalculateFifo(newTicker);

        // 6. Si el ticker cambió, recalcular también el antiguo
        if (!oldTicker.equals(newTicker)) {
            fifoService.recalculateFifo(oldTicker);
        }

        return op;
    }

    /**
     * Elimina una operación si no tiene registros de venta generados.
     * Para compras: elimina también el FifoLot (solo si no ha sido consumido parcialmente).
     * Para ventas: revierte los SaleRecords y restaura los lotes.
     */
    @Transactional
    public void delete(Long id) {
        Operation op = operationRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Operación no encontrada: " + id));

        if (op.getType() != OperationType.SELL) {
            // Verificar que no haya ventas que dependan de este lote
            if (saleRecordRepo.existsByConsumedLot_Operation_Id(id)) {
                throw new IllegalStateException(
                        "No se puede eliminar esta operación porque hay ventas registradas que consumen este lote.");
            }
            fifoLotRepo.findAll().stream()
                    .filter(l -> l.getOperation().getId().equals(id))
                    .forEach(fifoLotRepo::delete);
            operationRepo.delete(op);
            // Para CANJE: la redistribución de costes queda revertida al recalcular
            if (op.getType() == OperationType.CANJE) {
                fifoService.recalculateFifo(op.getTicker().toUpperCase());
            }
        } else {
            fifoService.reverseSell(id);
            operationRepo.delete(op);
        }
    }

    private Operation buildOperation(OperationForm form) {
        Operation op = new Operation();
        op.setDate(form.getDate());
        op.setBroker(form.getBroker().trim());
        op.setType(form.getType());
        op.setTicker(form.getTicker().trim().toUpperCase());
        op.setAssetName(form.getAssetName().trim());
        op.setQuantity(form.getQuantity());
        op.setAeatGroup(form.getAeatGroup());
        op.setNotes(form.getNotes());

        if (form.getType() == OperationType.CANJE) {
            op.setTotal(BigDecimal.ZERO);
            op.setCommission(BigDecimal.ZERO);
            op.setPrice(BigDecimal.ZERO);
        } else {
            op.setTotal(form.getTotal());
            op.setCommission(form.getCommission() != null ? form.getCommission() : BigDecimal.ZERO);
            BigDecimal netAmount = op.getType() == OperationType.BUY
                    ? op.getTotal().subtract(op.getCommission())
                    : op.getTotal().add(op.getCommission());
            op.setPrice(netAmount.divide(op.getQuantity(), 6, RoundingMode.HALF_UP));
        }
        return op;
    }
}
