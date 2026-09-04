package com.raul.bolsa.service;

import com.raul.bolsa.domain.Operation;
import com.raul.bolsa.domain.OperationType;
import com.raul.bolsa.repository.FifoLotRepository;
import com.raul.bolsa.repository.OperationRepository;
import com.raul.bolsa.repository.SaleRecordRepository;
import com.raul.bolsa.repository.SplitRepository;
import com.raul.bolsa.web.dto.OperationForm;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * El {@code userId} llega como parámetro explícito, no desde el SecurityContext:
 * así el servicio es utilizable desde tests que conducen a varios usuarios.
 */
@Service
@RequiredArgsConstructor
public class OperationService {

    private final OperationRepository operationRepo;
    private final FifoLotRepository fifoLotRepo;
    private final SaleRecordRepository saleRecordRepo;
    private final SplitRepository splitRepo;
    private final FifoService fifoService;

    /** Las dos patas de un traspaso, que atan el coste de un valor al de otro. */
    private static final java.util.List<OperationType> TRANSFERS =
            java.util.List.of(OperationType.TRASPASO_OUT, OperationType.TRASPASO_IN);

    @Transactional
    public Operation save(Long userId, OperationForm form) {
        Operation op = buildOperation(userId, form);

        // Un traspaso no se puede resolver mirándolo solo a él: su coste sale de los lotes del
        // fondo de origen, y el reparto solo cuadra con el evento entero delante. Lo mismo vale
        // para cualquier operación de una cartera que ya tenga traspasos, porque insertarla
        // puede cambiar qué lotes consumió el traspaso.
        if (op.getType().isTransfer() || operationRepo.existsByUserIdAndTypeIn(userId, TRANSFERS)) {
            operationRepo.save(op);
            if (op.getType().createsOwnLot()) {
                fifoService.createLot(op);
            }
            fifoService.recalculateAll(userId);
            return op;
        }

        // Recalcular si: hay ventas con SaleRecords de fecha >= nueva op, hay ventas pendientes,
        // o la operación es anterior a un split ya registrado. Sin lo último su lote nacería sin
        // aplicar ese split y se quedaría con menos títulos de los que le corresponden, que es
        // justo lo que pasa al cargar operaciones antiguas en una cartera que ya tiene splits.
        boolean needsRecalc = saleRecordRepo.existsByUserIdAndTickerAndSaleDateGreaterThanEqual(
                userId, op.getTicker(), op.getDate())
                || operationRepo.existsByUserIdAndTickerAndTypeAndPendingQtyGreaterThan(
                        userId, op.getTicker(), OperationType.SELL, BigDecimal.ZERO)
                || splitRepo.existsByUserIdAndTickerAndDateAfter(
                        userId, op.getTicker(), op.getDate());

        if (op.getType() != OperationType.SELL) {
            operationRepo.save(op);
            fifoService.createLot(op);
            if (needsRecalc) {
                fifoService.recalculateFifo(userId, op.getTicker());
            } else if (op.getType() == OperationType.CANJE) {
                fifoService.processCanje(op);
            }
        } else {
            operationRepo.save(op);
            if (needsRecalc) {
                fifoService.recalculateFifo(userId, op.getTicker());
            } else {
                fifoService.processSell(op);
            }
        }

        return op;
    }

    /**
     * Da de alta la operación sin tocar el FIFO. Solo para cargas masivas, que meten cientos de
     * filas de golpe: recalcular en cada una sería cuadrático y, con traspasos de por medio, los
     * resultados intermedios no significarían nada porque falta el resto del evento. Quien la use
     * está obligado a llamar después a {@link FifoService#recalculateAll(Long)}.
     */
    @Transactional
    public Operation saveDeferred(Long userId, OperationForm form) {
        Operation op = buildOperation(userId, form);
        operationRepo.save(op);
        if (op.getType().createsOwnLot()) {
            fifoService.createLot(op);
        }
        return op;
    }

    /**
     * Edita una operación: resetea el FIFO del ticker, elimina la operación antigua
     * y la re-inserta con los nuevos datos. El recálculo garantiza la consistencia
     * independientemente de fechas o cambios de tipo.
     */
    @Transactional
    public Operation update(Long userId, Long id, OperationForm form) {
        Operation existing = requireOwned(userId, id);
        String oldTicker = existing.getTicker();
        String newTicker = form.getTicker().trim().toUpperCase();

        // 1. Resetear FIFO del ticker antiguo: borrar SaleRecords y restaurar lots
        saleRecordRepo.deleteByUserIdAndTicker(userId, oldTicker);
        fifoLotRepo.findByUserIdAndTickerOrderByPurchaseDateAscIdAsc(userId, oldTicker).forEach(lot -> {
            lot.setRemainingQty(lot.getInitialQty());
            lot.setRemainingCost(lot.getInitialCost());
            fifoLotRepo.save(lot);
        });

        // 2. Eliminar los lotes de esta operación. Un TRASPASO_IN tiene uno por cada fecha de
        // adquisición heredada del origen, así que aquí puede haber más de uno.
        if (!existing.getType().reducesPosition()) {
            fifoLotRepo.findAllByOperation_Id(id).forEach(fifoLotRepo::delete);
        }

        // 3. Eliminar la operación antigua
        operationRepo.delete(existing);

        // 4. Guardar la nueva operación y crear su lot si es compra o canje
        Operation op = buildOperation(userId, form);
        operationRepo.save(op);
        if (op.getType().createsOwnLot()) {
            fifoService.createLot(op);
        }

        // 5. Recalcular FIFO para el ticker nuevo (reprocesa todas las ventas en orden).
        //    recalculateFifo() se encarga de pasar a la cartera entera si hay traspasos.
        fifoService.recalculateFifo(userId, newTicker);

        // 6. Si el ticker cambió, recalcular también el antiguo
        if (!oldTicker.equals(newTicker)) {
            fifoService.recalculateFifo(userId, oldTicker);
        }

        return op;
    }

    /**
     * Elimina una operación si no tiene registros de venta generados.
     * Para compras: elimina también el FifoLot (solo si no ha sido consumido parcialmente).
     * Para ventas: revierte los SaleRecords y restaura los lotes.
     */
    @Transactional
    public void delete(Long userId, Long id) {
        Operation op = requireOwned(userId, id);

        if (op.getType().reducesPosition()) {
            if (op.getType() == OperationType.SELL) {
                fifoService.reverseSell(userId, id);
            }
            operationRepo.delete(op);
            // La salida de un traspaso no deja SaleRecords que revertir: el coste que se llevó
            // solo vuelve a su sitio reproduciendo la cartera entera.
            if (op.getType() == OperationType.TRASPASO_OUT) {
                fifoService.recalculateAll(userId);
            }
        } else {
            // Verificar que no haya ventas que dependan de este lote
            if (saleRecordRepo.existsByUserIdAndConsumedLot_Operation_Id(userId, id)) {
                throw new IllegalStateException(
                        "No se puede eliminar esta operación porque hay ventas registradas que consumen este lote.");
            }
            fifoLotRepo.findAllByOperation_Id(id).forEach(fifoLotRepo::delete);
            operationRepo.delete(op);
            // Para CANJE: la redistribución de costes queda revertida al recalcular
            if (op.getType() == OperationType.CANJE) {
                fifoService.recalculateFifo(userId, op.getTicker().toUpperCase());
            } else if (op.getType() == OperationType.TRASPASO_IN) {
                fifoService.recalculateAll(userId);
            }
        }
    }

    /**
     * Carga la operación comprobando el propietario. Para un usuario ajeno el resultado
     * es indistinguible de que no exista.
     */
    private Operation requireOwned(Long userId, Long id) {
        return operationRepo.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new IllegalArgumentException("Operación no encontrada: " + id));
    }

    private Operation buildOperation(Long userId, OperationForm form) {
        Operation op = new Operation();
        op.setUserId(userId);
        op.setDate(form.getDate());
        op.setBroker(form.getBroker().trim());
        op.setType(form.getType());
        op.setTicker(form.getTicker().trim().toUpperCase());
        op.setAssetName(form.getAssetName().trim());
        op.setQuantity(form.getQuantity());
        op.setAeatGroup(form.getAeatGroup());
        op.setNotes(form.getNotes());
        op.setTransferId(form.getTransferId());

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
