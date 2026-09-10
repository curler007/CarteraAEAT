package com.raul.bolsa;

import com.raul.bolsa.domain.AeatGroup;
import com.raul.bolsa.domain.AppUser;
import com.raul.bolsa.domain.OperationType;
import com.raul.bolsa.repository.AppUserRepository;
import com.raul.bolsa.service.OperationService;
import com.raul.bolsa.service.PortfolioValuationService;
import com.raul.bolsa.web.dto.OperationForm;
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
 * La cartera de una fecha pasada se reconstruye desde las operaciones, y no desde los lotes vivos.
 *
 * <p>Un lote de traspaso hereda la fecha de adquisición del fondo de origen pero lleva el ISIN del
 * destino. Mirando los lotes parecería que el fondo de destino se tenía desde mucho antes de que
 * el dinero llegara a él, y sus periodos se valorarían a precios de cuando ni siquiera estaba ahí.
 */
@SpringBootTest
class HistoricHoldingsTest {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) throws IOException {
        Path db = Files.createTempDirectory("bolsa-historic-").resolve("test.db");
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + db.toAbsolutePath());
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
        registry.add("app.security.username", () -> "hist");
        registry.add("app.security.password", () -> "hist");
    }

    @Autowired AppUserRepository userRepo;
    @Autowired OperationService operationService;
    @Autowired PortfolioValuationService valuation;

    private Long uid;

    @BeforeEach
    void setUp(TestInfo info) {
        // Un usuario por método: los dos comparten el mismo fichero de base de datos y con un
        // usuario común el segundo test heredaría las operaciones que sembró el primero.
        AppUser user = TestUsers.create(userRepo, "hist-" + info.getTestMethod().orElseThrow().getName());
        uid = user.getId();
        // Compra en el fondo A, y en 2025 el dinero se traspasa entero al fondo B
        operationService.save(uid, form(OperationType.BUY, "2024-01-10", "IE00000000A1", "100", "1000", null));
        operationService.save(uid, form(OperationType.TRASPASO_OUT, "2025-10-13", "IE00000000A1", "100", "1200", "T1"));
        operationService.save(uid, form(OperationType.TRASPASO_IN, "2025-10-13", "IE00000000B2", "60", "1200", "T1"));
    }

    @Test
    @DisplayName("Antes del traspaso la cartera es el fondo de origen, no el de destino")
    void beforeTransferHoldsTheOriginFund() {
        Map<String, BigDecimal> antes = valuation.holdingsAt(uid, LocalDate.parse("2025-09-07"));

        assertTrue(antes.containsKey("IE00000000A1"), "el fondo de origen debería estar en cartera");
        assertEquals(0, new BigDecimal("100").compareTo(antes.get("IE00000000A1")), "cartera: " + antes);
        assertFalse(antes.containsKey("IE00000000B2"),
                "el fondo de destino no se tenía todavía: el dinero no llegó hasta octubre");
    }

    @Test
    @DisplayName("Después del traspaso la cartera es el fondo de destino, no el de origen")
    void afterTransferHoldsTheTargetFund() {
        Map<String, BigDecimal> despues = valuation.holdingsAt(uid, LocalDate.parse("2025-10-14"));

        assertEquals(0, new BigDecimal("60").compareTo(despues.get("IE00000000B2")), "cartera: " + despues);
        assertFalse(despues.containsKey("IE00000000A1"), "el origen quedó a cero y no debe aparecer");
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
