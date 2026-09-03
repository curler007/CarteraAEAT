package com.raul.bolsa;

import com.raul.bolsa.domain.AeatGroup;
import com.raul.bolsa.domain.AppUser;
import com.raul.bolsa.domain.OperationType;
import com.raul.bolsa.repository.AppUserRepository;
import com.raul.bolsa.security.AppUserPrincipal;
import com.raul.bolsa.service.OperationService;
import com.raul.bolsa.web.dto.OperationForm;
import org.hamcrest.Matchers;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * El listado se pinta con traspasos dentro. Merece un test de verdad: una expresión mal escrita en
 * la plantilla no la ve el compilador y solo revienta al abrir la página.
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
class OperationsPageRenderTest {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) throws IOException {
        Path db = Files.createTempDirectory("bolsa-render-").resolve("test.db");
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
        // El filtro de seguridad está desactivado, así que el principal se pone a mano: es lo que
        // CurrentUser lee para saber de quién son los datos.
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new AppUserPrincipal(user), null, List.of()));

        Long uid = user.getId();
        operationService.save(uid, form(OperationType.BUY, "2024-01-10",
                "IE0000000001", "100", "1000", null));
        operationService.save(uid, form(OperationType.TRASPASO_OUT, "2024-06-03",
                "IE0000000001", "60", "750", "T1"));
        operationService.save(uid, form(OperationType.TRASPASO_IN, "2024-06-05",
                "IE0000000002", "37.5", "750", "T1"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("El listado se pinta con las dos patas de un traspaso")
    void rendersTransfers() throws Exception {
        // Sin la cadena de filtros no hay token CSRF y la plantilla común lo pinta en cada
        // formulario, así que se pone a mano para poder llegar a renderizar el listado.
        CsrfToken csrf = new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "token-de-prueba");

        mvc.perform(get("/operations")
                        .requestAttr(CsrfToken.class.getName(), csrf)
                        .requestAttr("_csrf", csrf))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Traspaso ↗")))
                .andExpect(content().string(Matchers.containsString("Traspaso ↘")));
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
