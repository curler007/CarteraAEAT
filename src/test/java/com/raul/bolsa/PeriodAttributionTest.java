package com.raul.bolsa;

import com.raul.bolsa.domain.AeatGroup;
import com.raul.bolsa.domain.AppUser;
import com.raul.bolsa.domain.OperationType;
import com.raul.bolsa.repository.AppUserRepository;
import com.raul.bolsa.service.OperationService;
import com.raul.bolsa.service.PortfolioValuationService;
import com.raul.bolsa.web.dto.OperationForm;
import com.raul.bolsa.web.dto.PeriodFlow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * El reparto de un periodo entre los valores que lo mueven.
 *
 * <p>La tarjeta mide la cartera entera: valor de hoy más lo cobrado por ventas, contra el valor de
 * entonces más lo aportado después. Para poder decir <em>quién</em> la mueve hace falta la misma
 * cuenta valor a valor, y ahí las dos patas de un traspaso dejan de anularse: el fondo que suelta
 * el dinero tiene que apuntarse esa salida, y el que lo recibe, la entrada. Sin eso el origen
 * cargaría con una pérdida de su tamaño entero y el destino con la ganancia simétrica.
 */
@SpringBootTest
class PeriodAttributionTest {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) throws IOException {
        Path db = Files.createTempDirectory("bolsa-attribution-").resolve("test.db");
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + db.toAbsolutePath());
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
        registry.add("app.security.username", () -> "attr");
        registry.add("app.security.password", () -> "attr");
    }

    @Autowired AppUserRepository userRepo;
    @Autowired OperationService operationService;
    @Autowired PortfolioValuationService valuation;

    private Long uid;

    /** Un usuario por método: comparten fichero de base de datos. */
    @BeforeEach
    void setUp(TestInfo info) {
        AppUser user = TestUsers.create(userRepo, "attr-" + info.getTestMethod().orElseThrow().getName());
        uid = user.getId();
    }

    private static final LocalDate INICIO = LocalDate.parse("2025-01-01");

    @Test
    @DisplayName("Un traspaso es salida en el fondo de origen y entrada en el de destino")
    void transferCountsAsOutflowAndInflow() {
        operationService.save(uid, form(OperationType.BUY, "2024-06-10", "IE00000000A1", "100", "1000", null));
        operationService.save(uid, form(OperationType.SELL, "2025-03-01", "IE00000000A1", "40", "400", null));
        operationService.save(uid, form(OperationType.TRASPASO_OUT, "2025-06-01", "IE00000000A1", "60", "700", "T1"));
        operationService.save(uid, form(OperationType.TRASPASO_IN, "2025-06-05", "IE00000000B2", "50", "695", "T1"));
        operationService.save(uid, form(OperationType.BUY, "2025-08-01", "IE00000000B2", "10", "200", null));

        Map<String, PeriodFlow> flows = valuation.flowsAfter(uid, INICIO);

        assertEquals(0, new BigDecimal("1100").compareTo(flows.get("IE00000000A1").outflow()),
                "del origen salieron la venta y el traspaso: " + flows);
        assertEquals(0, BigDecimal.ZERO.compareTo(flows.get("IE00000000A1").inflow()),
                "y no entró nada en él");
        assertEquals(0, new BigDecimal("895").compareTo(flows.get("IE00000000B2").inflow()),
                "en el destino entraron el traspaso y la compra posterior: " + flows);

        // Lo que se quedó por el camino —salieron 700 € y entraron 695, con el dinero cuatro días
        // fuera del mercado— no es de ninguno de los dos fondos, y por eso el periodo lo lleva
        // aparte en transferDrift: dentro de la tarjeta está, así que el reparto tiene que
        // enseñarlo para volver a sumar la misma cifra.
        assertEquals(0, new BigDecimal("695").compareTo(flows.get("IE00000000B2").inflow()
                        .subtract(new BigDecimal("200"))),
                "la entrada del traspaso se apunta por lo que llegó, no por lo que salió");
    }

    @Test
    @DisplayName("Una operación anterior al periodo no entra en el reparto")
    void operationsBeforeTheWindowAreIgnored() {
        operationService.save(uid, form(OperationType.BUY, "2024-06-10", "IE00000000A1", "100", "1000", null));

        assertTrue(valuation.flowsAfter(uid, INICIO).isEmpty(),
                "la compra es de antes: su valor ya está dentro del punto de partida");
    }

    @Test
    @DisplayName("La salida sin compra que la respalde entra como dinero nuevo en su propio valor")
    void unmatchedOutflowAlsoCountsAsInflow() {
        // Sin ninguna compra previa: el traspaso saca títulos que en los libros nunca entraron.
        operationService.save(uid, form(OperationType.TRASPASO_OUT, "2025-06-01", "IE00000000C3", "30", "600", "T2"));
        operationService.save(uid, form(OperationType.TRASPASO_IN, "2025-06-01", "IE00000000D4", "30", "600", "T2"));

        PeriodFlow huerfano = valuation.flowsAfter(uid, INICIO).get("IE00000000C3");

        assertEquals(0, new BigDecimal("600").compareTo(huerfano.outflow()), "salieron 600 €");
        assertEquals(0, new BigDecimal("600").compareTo(huerfano.inflow()),
                "y los mismos 600 € aparecieron sin compra detrás, así que no son ganancia de nadie");
        assertFalse(huerfano.ticker().isBlank(), "la fila necesita un nombre que enseñar");
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
