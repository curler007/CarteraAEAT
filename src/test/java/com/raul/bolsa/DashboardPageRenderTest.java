package com.raul.bolsa;

import com.raul.bolsa.domain.AeatGroup;
import com.raul.bolsa.domain.AppUser;
import com.raul.bolsa.domain.OperationType;
import com.raul.bolsa.repository.AppUserRepository;
import com.raul.bolsa.security.AppUserPrincipal;
import com.raul.bolsa.service.OperationService;
import com.raul.bolsa.web.dto.OperationForm;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * El dashboard deja huecos que rellena el navegador con las cotizaciones. Los anclajes de esos
 * huecos son marcado de plantilla, que el compilador no mira: si uno se cae o se renombra, el
 * JavaScript falla en silencio y la página se queda con los puntos suspensivos puestos.
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
class DashboardPageRenderTest {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) throws IOException {
        Path db = Files.createTempDirectory("bolsa-dashboard-").resolve("test.db");
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + db.toAbsolutePath());
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
        registry.add("app.security.username", () -> "render");
        registry.add("app.security.password", () -> "render");
    }

    @Autowired MockMvc mvc;
    @Autowired AppUserRepository userRepo;
    @Autowired OperationService operationService;

    @BeforeEach
    void setUp() {
        AppUser user = TestUsers.create(userRepo, "render");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new AppUserPrincipal(user), null, List.of()));

        Long uid = user.getId();
        operationService.save(uid, form(OperationType.BUY, "2024-01-10", "IE0000000001", "100", "1000"));
        operationService.save(uid, form(OperationType.BUY, "2024-03-05", "IE0000000002", "50", "500"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("El dashboard pinta los anclajes de cotización de cada posición")
    void rendersQuoteAnchors() throws Exception {
        CsrfToken csrf = new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "token-de-prueba");

        String html = mvc.perform(get("/dashboard")
                        .requestAttr(CsrfToken.class.getName(), csrf)
                        .requestAttr("_csrf", csrf))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Aviso de la parte de la cartera que no ha podido valorarse
        assertTrue(html.contains("id=\"global-unvalued\""), "falta el aviso de lo no valorado");

        // Una marca por posición: es donde se cuelga el icono y el tooltip del motivo
        assertEquals(2, html.split("class=\"quote-flag ms-1\"", -1).length - 1,
                "cada posición debe llevar su marca de cotización");

        // El punto de partida de cada periodo viaja en la fila, no en el JavaScript
        assertTrue(html.contains("data-held-year="), "falta la base del periodo anual");

        // Recorrido completo: lo invertido lo pinta Thymeleaf, lo ganado lo completa el navegador
        assertTrue(html.contains("Desde el principio"), "falta el resumen de toda la vida");
        assertTrue(html.contains("Comprado"), "falta la etiqueta de lo comprado");
        assertTrue(html.contains("1.500,00 €"), "lo comprado no suma las dos compras");
        assertTrue(html.contains("id=\"lifetime-gain\""), "falta el hueco de lo ganado");
        assertTrue(html.contains("id=\"lifetime-pct\""), "falta el hueco del porcentaje");
        assertTrue(html.contains("id=\"irr-flag\""), "falta la marca de TIR aproximada");

        // Los flujos de caja de la TIR viajan serializados a JSON. Si el inlining los escupiera
        // como un toString() de Java, el navegador no podría leerlos y la TIR no saldría.
        assertTrue(html.contains("\"date\":\"2024-01-10\""), "los flujos no llegan como JSON");
        assertTrue(html.contains("-1000"), "la compra debe ser un flujo negativo");

        // Las expresiones dentro de <script> tienen que haberse sustituido por números: si el
        // inlining dejase de aplicarse, el JavaScript reventaría entero y la página se quedaría
        // muda sin que ningún test lo notara.
        assertFalse(html.contains("[[${"), "quedan expresiones de Thymeleaf sin sustituir");
    }

    private static OperationForm form(OperationType type, String date, String isin,
                                      String qty, String total) {
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
        return f;
    }
}
