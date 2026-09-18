package com.raul.bolsa.service;

import com.raul.bolsa.domain.Operation;
import com.raul.bolsa.domain.OperationType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * El dinero que un día estaba viajando de un fondo a otro.
 *
 * <p>Un traspaso tarda días y en ese hueco el dinero no está en ninguna posición: los libros no
 * tienen cuenta de efectivo, así que desaparece de la cartera al salir y reaparece al llegar. Una
 * ventana que empieza justo en ese hueco no lo contaba en el valor inicial, lo encontraba en el de
 * hoy, y como un traspaso no es dinero nuevo tampoco se restaba: el periodo se apuntaba ese
 * importe entero como revalorización. Pasó de verdad con la tarjeta de 1 mes del 18/08/2026, que
 * marcaba +1.328 € cuando la cartera se había movido −203 €.
 */
class InTransitTest {

    private static final LocalDate CORTE = LocalDate.parse("2026-08-18");

    /**
     * La tanda INV:2026-08-13 tal cual está en la base: siete salidas entre el 13 y el 18 de
     * agosto, una entrada que ya había llegado el propio día del corte y tres posteriores. Importa
     * que esté entera, porque lo que se mide es la resta —lo salido menos lo ya llegado— y con
     * todas las entradas después del corte esa resta no se ejercita.
     */
    private static List<Operation> tandaReal() {
        return List.of(
                op(OperationType.TRASPASO_OUT, "2026-08-13", "FRONTIER", "223.31", "T1"),
                op(OperationType.TRASPASO_OUT, "2026-08-14", "EUROPE", "1023.61", "T1"),
                op(OperationType.TRASPASO_OUT, "2026-08-14", "US500", "1172.37", "T1"),
                op(OperationType.TRASPASO_OUT, "2026-08-17", "EM", "711.82", "T1"),
                op(OperationType.TRASPASO_OUT, "2026-08-17", "REALSTATE", "184.28", "T1"),
                op(OperationType.TRASPASO_OUT, "2026-08-17", "SMALLCAP", "221.22", "T1"),
                op(OperationType.TRASPASO_OUT, "2026-08-18", "JAPAN", "190.76", "T1"),
                op(OperationType.TRASPASO_IN, "2026-08-18", "MONETARIO", "2195.98", "T1"),
                op(OperationType.TRASPASO_IN, "2026-08-19", "MONETARIO", "1156.35", "T1"),
                op(OperationType.TRASPASO_IN, "2026-08-20", "MONETARIO", "184.28", "T1"),
                op(OperationType.TRASPASO_IN, "2026-08-21", "MONETARIO", "190.76", "T1"));
    }

    @Test
    @DisplayName("Lo que había salido y no había llegado cuenta en el fondo donde aterriza")
    void moneyInTheAirCountsWhereItLands() {
        Map<String, BigDecimal> enTransito =
                PortfolioValuationService.inTransitAt(tandaReal(), CORTE);

        assertEquals(1, enTransito.size(), "solo el destino recibe: " + enTransito);
        // 3.727,37 salidos hasta el corte menos 2.195,98 ya llegados ese mismo día.
        assertEquals(0, new BigDecimal("1531.39").compareTo(enTransito.get("MONETARIO")),
                "es el importe que la tarjeta de 1 mes se apuntaba como revalorización");
    }

    @Test
    @DisplayName("Lo ya llegado antes del corte no vuelve a contarse")
    void whatHadAlreadyArrivedIsNotCountedTwice() {
        // El mismo día del corte había llegado una entrada de 2.195,98 €: ese dinero ya estaba en
        // el monetario y lo valora su cotización, así que contarlo otra vez lo duplicaría.
        BigDecimal enElAire = PortfolioValuationService.inTransitAt(tandaReal(), CORTE)
                .get("MONETARIO");
        BigDecimal salido = new BigDecimal("3727.37");

        assertEquals(0, salido.subtract(new BigDecimal("2195.98")).compareTo(enElAire),
                "en el aire quedaba lo salido menos lo ya aterrizado");
    }

    @Test
    @DisplayName("Un traspaso que ya había llegado no deja nada en el aire")
    void completedTransferLeavesNothing() {
        List<Operation> legs = List.of(
                op(OperationType.TRASPASO_OUT, "2026-08-10", "EUROPE", "1000", "T1"),
                op(OperationType.TRASPASO_IN, "2026-08-12", "MONETARIO", "1000", "T1"));

        assertTrue(PortfolioValuationService.inTransitAt(legs, CORTE).isEmpty(),
                "el dinero ya estaba en el fondo de destino, que es quien lo valora");
    }

    @Test
    @DisplayName("Un traspaso entero posterior al corte tampoco: ese sí es movimiento del periodo")
    void transferAfterTheCutIsNotInTransit() {
        List<Operation> legs = List.of(
                op(OperationType.TRASPASO_OUT, "2026-08-25", "MONETARIO", "3724.88", "T2"),
                op(OperationType.TRASPASO_IN, "2026-08-26", "EUROPE", "3724.79", "T2"));

        assertTrue(PortfolioValuationService.inTransitAt(legs, CORTE).isEmpty(),
                "salió y llegó dentro de la ventana: lo explican la salida y la entrada");
    }

    @Test
    @DisplayName("Solo se reparte lo que estaba en el aire, no la entrada entera")
    void onlyWhatWasInTheAirIsAssigned() {
        // Salieron 1.000 antes del corte y 500 después; la entrada posterior trae los 1.500.
        List<Operation> legs = List.of(
                op(OperationType.TRASPASO_OUT, "2026-08-14", "EUROPE", "1000", "T1"),
                op(OperationType.TRASPASO_OUT, "2026-08-20", "EM", "500", "T1"),
                op(OperationType.TRASPASO_IN, "2026-08-21", "MONETARIO", "1500", "T1"));

        assertEquals(0, new BigDecimal("1000").compareTo(
                        PortfolioValuationService.inTransitAt(legs, CORTE).get("MONETARIO")),
                "los 500 que salieron dentro de la ventana son salida del periodo, no tránsito");
    }

    private static Operation op(OperationType type, String date, String ticker,
                                String total, String transferId) {
        Operation o = new Operation();
        o.setType(type);
        o.setDate(LocalDate.parse(date));
        o.setTicker(ticker);
        o.setAssetName(ticker);
        o.setTotal(new BigDecimal(total));
        o.setTransferId(transferId);
        return o;
    }
}
