package com.raul.bolsa.web.dto;

import com.raul.bolsa.domain.Operation;
import com.raul.bolsa.domain.OperationType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * Una salida —venta o traspaso— que el FIFO no pudo casar con ningún lote: salieron títulos cuya
 * entrada en la cartera no consta en ninguna operación.
 *
 * <p>Aparece al importar solo una parte del histórico. El caso típico es el fondo monetario que
 * las carteras gestionadas usan de aparcamiento: el dinero entra en él desde un contrato y sale
 * hacia otro, y el extracto de movimientos de Inversis va por contrato, así que importando solo
 * uno de los dos el monetario sale de la cartera sin haber entrado nunca en ella.
 *
 * <p>Sin origen, {@code FifoService} da de alta la entrada del traspaso como si fuese una compra
 * por el valor que entró. Es lo más parecido a la realidad que puede hacer con lo que tiene, pero
 * deja dos secuelas que hay que enseñar en vez de callar: el coste de adquisición pasa a ser el
 * valor del día del traspaso en lugar de lo que se pagó de verdad —al vender, la ganancia saldría
 * corta ante Hacienda— y ese dinero entra en la cartera sin ninguna compra detrás, de modo que
 * los periodos lo tomarían por ganancia si no se contase aparte.
 *
 * <p>Una venta también puede quedarse sin casar, y entonces la secuela es otra: la parte sin lote
 * no genera ningún registro de venta, así que no llega al informe de la AEAT. Por eso el aviso
 * habla del coste que falta y no del destino que se dio de alta, que solo existe en el traspaso.
 *
 * @param pendingQty títulos que salieron sin lote que los respaldara
 * @param value      lo que valían esos títulos, en euros
 */
public record MissingOrigin(Long operationId, LocalDate date, OperationType type, String ticker,
                            String isin, BigDecimal pendingQty, BigDecimal value) {

    public static MissingOrigin of(Operation op) {
        return new MissingOrigin(op.getId(), op.getDate(), op.getType(), op.getTicker(),
                op.getAssetName(), op.getPendingQty(), unmatchedValue(op));
    }

    /**
     * Valor en euros de la parte de una salida que no casó con ningún lote, o cero si casó entera.
     *
     * <p>Se prorratea el importe de la operación por los títulos que quedaron sin respaldo, y no
     * se toma el importe completo: una salida puede casar a medias —la mitad de los títulos
     * constan y la otra mitad no— y solo esa mitad es dinero cuyo origen se desconoce.
     */
    public static BigDecimal unmatchedValue(Operation op) {
        BigDecimal pending = op.getPendingQty();
        if (pending == null || pending.signum() <= 0) return BigDecimal.ZERO;
        BigDecimal qty = op.getQuantity();
        if (qty == null || qty.signum() == 0) return BigDecimal.ZERO;
        return op.getTotal().multiply(pending).divide(qty, 6, RoundingMode.HALF_UP);
    }
}
