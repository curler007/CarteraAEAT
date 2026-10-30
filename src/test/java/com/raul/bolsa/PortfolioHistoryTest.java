package com.raul.bolsa;

import com.raul.bolsa.domain.AeatGroup;
import com.raul.bolsa.domain.AppUser;
import com.raul.bolsa.domain.OperationType;
import com.raul.bolsa.repository.AppUserRepository;
import com.raul.bolsa.service.OperationService;
import com.raul.bolsa.service.PortfolioValuationService;
import com.raul.bolsa.service.QuoteService;
import com.raul.bolsa.web.dto.OperationForm;
import com.raul.bolsa.web.dto.PortfolioHistory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * La serie de la gráfica: valor de la cartera frente a lo aportado, día a día.
 *
 * <p>Lo que se comprueba es que la distancia entre las dos curvas sea la plusvalía latente: la
 * mueve el mercado, y una venta solo por lo que realiza. Comprar o traspasar no la tocan.
 */
@SpringBootTest
class PortfolioHistoryTest {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) throws IOException {
        Path db = Files.createTempDirectory("bolsa-history-").resolve("test.db");
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + db.toAbsolutePath());
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
        registry.add("app.security.username", () -> "hist");
        registry.add("app.security.password", () -> "hist");
    }

    @Autowired AppUserRepository userRepo;
    @Autowired OperationService operationService;
    @Autowired PortfolioValuationService valuation;
    @MockBean QuoteService quoteService;

    private static final LocalDate DESDE = LocalDate.parse("2025-01-01");
    private static final LocalDate HASTA = LocalDate.parse("2025-12-31");
    /** El día que el valor A pasa de 10 € a 12 €: la única subida de mercado del año. */
    private static final LocalDate SUBIDA = LocalDate.parse("2025-06-01");

    private Long uid;
    private QuoteService.Historic historic;

    /** Un usuario por método: comparten fichero de base de datos. */
    @BeforeEach
    void setUp(TestInfo info) {
        AppUser user = TestUsers.create(userRepo, "hist-" + info.getTestMethod().orElseThrow().getName());
        uid = user.getId();

        historic = mock(QuoteService.Historic.class);
        when(quoteService.openHistoric()).thenReturn(historic);
        when(historic.seriesOf(anyString())).thenAnswer(inv ->
                Optional.of(new QuoteService.Series("Y-" + inv.getArgument(0), DESDE.minusYears(5))));
        when(historic.closeAt(anyString(), any())).thenAnswer(inv -> {
            String symbol = inv.getArgument(0);
            LocalDate at = inv.getArgument(1);
            if (symbol.equals("Y-IE00000000A1"))
                return Optional.of(money(at.isBefore(SUBIDA) ? "10" : "12"));
            if (symbol.equals("Y-IE00000000B2")) return Optional.of(money("20"));
            return Optional.empty();
        });
        when(historic.toEurAt(any(), anyString(), any())).thenAnswer(inv ->
                Optional.of((BigDecimal) inv.getArgument(0)));
    }

    @Test
    @DisplayName("El primer día ya enseña lo ganado antes de la ventana")
    void costCountsFromTheFirstOperation() {
        operationService.save(uid, form(OperationType.BUY, "2024-03-01", "IE00000000A1", "100", "900", null));

        PortfolioHistory h = valuation.history(uid, DESDE, HASTA);

        assertEquals(365, h.points().size(), "un punto por día natural");
        PortfolioHistory.Point first = h.points().get(0), last = h.points().get(364);
        assertEq("1000", first.value());
        assertEq("900", first.cost(),
                "el coste es lo que se pagó, y los 100 € de diferencia son de antes de la ventana");
        assertEq("1200", last.value());
        assertEq("900", last.cost(), "sin compras ni ventas, el coste no se mueve");
        assertTrue(h.missing().isEmpty());
    }

    @Test
    @DisplayName("Comprar no mueve el beneficio, y vender lo baja justo por lo realizado")
    void buysAreNeutralAndSalesRealize() {
        operationService.save(uid, form(OperationType.BUY, "2024-03-01", "IE00000000A1", "100", "900", null));
        operationService.save(uid, form(OperationType.BUY, "2025-03-10", "IE00000000B2", "10", "200", null));
        operationService.save(uid, form(OperationType.SELL, "2025-09-10", "IE00000000A1", "50", "600", null));

        PortfolioHistory h = valuation.history(uid, DESDE, HASTA);

        PortfolioHistory.Point trasCompra = point(h, "2025-03-10");
        assertEq("1200", trasCompra.value());
        assertEq("1100", trasCompra.cost(), "la compra entra en las dos curvas a la vez");
        assertEq("100", trasCompra.value().subtract(trasCompra.cost()));

        PortfolioHistory.Point antesVenta = point(h, "2025-09-09");
        PortfolioHistory.Point trasVenta = point(h, "2025-09-10");
        assertEq("800", trasVenta.value(), "quedan 50 de A a 12 € y los 10 de B a 20 €");
        assertEq("650", trasVenta.cost(),
                "sale el coste de lo vendido, 50 × 9 €, no lo cobrado: es el coste vivo de los lotes");
        assertEq("300", antesVenta.value().subtract(antesVenta.cost()), "la víspera, 100 títulos × 3 €");
        assertEq("150", trasVenta.value().subtract(trasVenta.cost()),
                "tras vender queda latente la mitad: la otra mitad se realizó y es la etiqueta");

        assertEquals(1, h.sales().size(), "la venta se marca en la gráfica");
        PortfolioHistory.Sale venta = h.sales().get(0);
        assertEquals("2025-09-10", venta.date());
        assertEq("600", venta.proceeds());
        assertEq("150", venta.gainLoss(), "cobró 600 € por 50 títulos que costaron 450 €");
    }

    @Test
    @DisplayName("Un traspaso no es dinero nuevo ni abre un hueco mientras viaja")
    void transferIsNeutral() {
        operationService.save(uid, form(OperationType.BUY, "2024-03-01", "IE00000000B2", "50", "1000", null));
        operationService.save(uid, form(OperationType.TRASPASO_OUT, "2025-03-10", "IE00000000B2", "50", "1000", "T1"));
        operationService.save(uid, form(OperationType.TRASPASO_IN, "2025-03-14", "IE00000000A1", "100", "1000", "T1"));

        PortfolioHistory h = valuation.history(uid, DESDE, HASTA);

        PortfolioHistory.Point enElAire = point(h, "2025-03-12");
        assertEq("1000", enElAire.value(), "el dinero que viaja sigue siendo de la cartera");
        assertEq("1000", enElAire.cost());
        assertTrue(h.sales().isEmpty(), "un traspaso no es una venta");
        assertEq("1000", point(h, "2025-03-14").cost(), "la llegada tampoco es dinero nuevo");
    }

    @Test
    @DisplayName("Un valor sin precio marca el día como incompleto y se nombra")
    void missingPriceIsFlagged() {
        operationService.save(uid, form(OperationType.BUY, "2024-03-01", "IE00000000A1", "100", "900", null));
        operationService.save(uid, form(OperationType.BUY, "2025-05-01", "IE00000000Z9", "1", "50", null));

        PortfolioHistory h = valuation.history(uid, DESDE, HASTA);

        assertTrue(point(h, "2025-04-30").complete());
        assertFalse(point(h, "2025-05-01").complete());
        assertEquals(java.util.List.of("IE00000000Z9"), h.missing());
    }

    private static PortfolioHistory.Point point(PortfolioHistory h, String date) {
        return h.points().stream().filter(p -> p.date().equals(date)).findFirst().orElseThrow();
    }

    private static void assertEq(String expected, BigDecimal actual) {
        assertEq(expected, actual, null);
    }

    private static void assertEq(String expected, BigDecimal actual, String message) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                (message == null ? "" : message + ": ") + "esperaba " + expected + ", salió " + actual);
    }

    private static QuoteService.Money money(String amount) {
        return new QuoteService.Money(new BigDecimal(amount), "EUR");
    }

    private static OperationForm form(OperationType type, String date, String isin,
                                      String qty, String total, String transferId) {
        OperationForm f = new OperationForm();
        f.setDate(LocalDate.parse(date));
        f.setType(type);
        f.setTicker(isin);
        f.setAssetName(isin);
        f.setBroker("MyInvestor");
        f.setQuantity(new BigDecimal(qty));
        f.setTotal(new BigDecimal(total));
        f.setCommission(BigDecimal.ZERO);
        f.setAeatGroup(AeatGroup.GROUP_2);
        f.setTransferId(transferId);
        return f;
    }
}
