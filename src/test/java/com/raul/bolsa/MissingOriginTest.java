package com.raul.bolsa;

import com.raul.bolsa.domain.AeatGroup;
import com.raul.bolsa.domain.AppUser;
import com.raul.bolsa.domain.Operation;
import com.raul.bolsa.domain.OperationType;
import com.raul.bolsa.repository.AppUserRepository;
import com.raul.bolsa.repository.OperationRepository;
import com.raul.bolsa.security.AppUserPrincipal;
import com.raul.bolsa.service.OperationService;
import com.raul.bolsa.service.PortfolioValuationService;
import com.raul.bolsa.service.QuoteService;
import com.raul.bolsa.web.dto.MissingOrigin;
import com.raul.bolsa.web.dto.OperationForm;
import com.raul.bolsa.web.dto.PeriodBaseline;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Un traspaso cuyo fondo de origen no se ha importado mete dinero en la cartera sin ninguna
 * compra detrás: el FIFO no encuentra lotes que consumir, da de alta el destino por el valor que
 * entró y la cartera acaba con coste que nadie pagó.
 *
 * <p>Es lo que pasa al importar una cartera gestionada y no la otra: el extracto de Inversis va
 * por contrato, y una cartera que se deshace en un fondo monetario deja las suscripciones al
 * monetario en un contrato y su reembolso en el otro.
 *
 * <p>Sin tratarlo, ese importe se leía como ganancia del periodo, porque la variación mide el
 * valor de hoy contra el de entonces más lo aportado por el camino, y ese dinero no estaba en
 * ninguno de los dos sitios.
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
class MissingOriginTest {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) throws IOException {
        Path db = Files.createTempDirectory("bolsa-missing-origin-").resolve("test.db");
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + db.toAbsolutePath());
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
        registry.add("app.security.username", () -> "origen");
        registry.add("app.security.password", () -> "origen");
    }

    /**
     * Ninguna comprobación de aquí valora posiciones: la fecha de referencia es anterior a todas
     * las operaciones, así que la cartera de ese día está vacía y no hay a quién pedirle precio.
     */
    @MockBean QuoteService quoteService;

    @Autowired MockMvc mvc;
    @Autowired AppUserRepository userRepo;
    @Autowired OperationRepository operationRepo;
    @Autowired OperationService operationService;
    @Autowired PortfolioValuationService valuation;

    /** Anterior a todo lo que siembra el test: deja la cartera de referencia vacía. */
    private static final LocalDate ANTES_DE_TODO = LocalDate.parse("2020-01-01");

    private Long uid;

    @BeforeEach
    void setUp(TestInfo info) {
        // Un usuario por método: los dos comparten fichero de base de datos y con un usuario
        // común el segundo test heredaría las operaciones que sembró el primero.
        AppUser user = TestUsers.create(userRepo,
                "origen-" + info.getTestMethod().orElseThrow().getName());
        uid = user.getId();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new AppUserPrincipal(user), null, List.of()));

        // Una compra normal, y un traspaso que saca un fondo del que no consta ninguna compra.
        operationService.save(uid, form(OperationType.BUY, "2024-01-10", "IE00000000A1", "100", "1000", null));
        operationService.save(uid, form(OperationType.TRASPASO_OUT, "2025-10-13", "IE00000000X9", "100", "1200", "T1"));
        operationService.save(uid, form(OperationType.TRASPASO_IN, "2025-10-13", "IE00000000B2", "60", "1200", "T1"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("La salida que no casó con ningún lote queda identificada con su importe")
    void detectaLaSalidaSinOrigen() {
        List<Operation> huerfanas = operationRepo
                .findByUserIdAndPendingQtyGreaterThanOrderByDateAscIdAsc(uid, BigDecimal.ZERO);

        assertEquals(1, huerfanas.size(), "el traspaso sin origen debería quedar señalado");
        MissingOrigin m = MissingOrigin.of(huerfanas.get(0));
        assertEquals("IE00000000X9", m.isin());
        assertEquals(0, new BigDecimal("100").compareTo(m.pendingQty()),
                "salieron los 100 títulos sin un solo lote detrás");
        assertEquals(0, new BigDecimal("1200").compareTo(m.value()),
                "el valor sin origen es el importe entero de la salida: " + m.value());
    }

    @Test
    @DisplayName("El dinero sin origen cuenta como aportación, no como ganancia del periodo")
    void elDineroSinOrigenCuentaComoAportacion() {
        PeriodBaseline b = baseline();

        assertEquals(0, BigDecimal.ZERO.compareTo(b.openingValue()),
                "antes de la primera operación no había cartera");
        assertEquals(0, new BigDecimal("2200.00").compareTo(b.boughtAfter()),
                "deberían sumarse la compra de 1.000 y los 1.200 aparecidos: " + b.boughtAfter());
    }

    @Test
    @DisplayName("El dashboard enseña qué valor falta, con fecha, ISIN, títulos e importe")
    void elDashboardEnseñaElDetalle() throws Exception {
        CsrfToken csrf = new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "token-de-prueba");

        String html = mvc.perform(get("/dashboard")
                        .requestAttr(CsrfToken.class.getName(), csrf)
                        .requestAttr("_csrf", csrf))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(html.contains("que nunca entró"), "falta el aviso de la salida sin origen");
        assertTrue(html.contains("IE00000000X9"), "el aviso debe decir de qué valor se trata");
        assertTrue(html.contains("13/10/2025"), "el aviso debe decir cuándo pasó");
        assertTrue(html.contains("1.200,00 €"), "el aviso debe decir cuánto dinero hay en juego");
    }

    @Test
    @DisplayName("Al registrar la compra que faltaba, el aviso y el ajuste desaparecen solos")
    void alImportarElOrigenSeCorrige() {
        operationService.save(uid, form(OperationType.BUY, "2024-06-01", "IE00000000X9", "100", "800", null));

        assertTrue(operationRepo
                        .findByUserIdAndPendingQtyGreaterThanOrderByDateAscIdAsc(uid, BigDecimal.ZERO)
                        .isEmpty(),
                "con la compra registrada el traspaso ya casa con su lote");
        assertEquals(0, new BigDecimal("1800.00").compareTo(baseline().boughtAfter()),
                "las aportaciones son ya solo las dos compras, sin nada aparecido");
    }

    private PeriodBaseline baseline() {
        return valuation.baselines(uid, Map.of("year", ANTES_DE_TODO)).get(0);
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
