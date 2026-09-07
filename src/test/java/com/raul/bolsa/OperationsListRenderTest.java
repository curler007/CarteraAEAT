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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * El listado marca cada ISIN con un punto que el navegador colorea según lo conozca Yahoo. El
 * hueco de ese punto es marcado de plantilla, que el compilador no mira: si la expresión se rompe,
 * la página entera devuelve un 500 y no hay nada que lo detecte hasta abrirla.
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
class OperationsListRenderTest {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) throws IOException {
        Path db = Files.createTempDirectory("bolsa-ops-").resolve("test.db");
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + db.toAbsolutePath());
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
        registry.add("app.security.username", () -> "ops");
        registry.add("app.security.password", () -> "ops");
    }

    @Autowired MockMvc mvc;
    @Autowired AppUserRepository userRepo;
    @Autowired OperationService operationService;

    @BeforeEach
    void setUp() {
        AppUser user = TestUsers.create(userRepo, "ops");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new AppUserPrincipal(user), null, List.of()));
        operationService.save(user.getId(), form("2024-01-10", "IE00000000A1", "100", "1000"));
        operationService.save(user.getId(), form("2024-03-05", "IE00000000B2", "50", "500"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Cada operación lleva el hueco del estado de su ISIN")
    void rendersIsinDots() throws Exception {
        CsrfToken csrf = new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "token-de-prueba");

        String html = mvc.perform(get("/operations")
                        .requestAttr(CsrfToken.class.getName(), csrf)
                        .requestAttr("_csrf", csrf))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertEquals(2, html.split("class=\"isin-dot", -1).length - 1,
                "cada fila de operación debe llevar su punto de estado");
        // El ISIN sigue leyéndose: el punto se añadió dentro de su celda, no en su lugar
        assertTrue(html.contains("IE00000000A1"), "falta el ISIN de la primera operación");
        assertTrue(html.contains("IE00000000B2"), "falta el ISIN de la segunda operación");
    }

    private static OperationForm form(String date, String isin, String qty, String total) {
        OperationForm f = new OperationForm();
        f.setDate(LocalDate.parse(date));
        f.setType(OperationType.BUY);
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
