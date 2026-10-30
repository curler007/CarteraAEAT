package com.raul.bolsa;

import com.raul.bolsa.domain.AppUser;
import com.raul.bolsa.repository.AppUserRepository;
import com.raul.bolsa.security.AppUserPrincipal;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * La página de la gráfica se renderiza entera.
 *
 * <p>Thymeleaf lee {@code [(...)]} y {@code [[...]]} dentro de un {@code <script>} como expresiones
 * suyas, y un array de JavaScript que empiece por paréntesis las dispara. Falla al renderizar, con
 * la respuesta ya a medio enviar: el navegador recibe media página y la ruedita no para nunca.
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
class ChartPageRenderTest {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) throws IOException {
        Path db = Files.createTempDirectory("bolsa-chart-").resolve("test.db");
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + db.toAbsolutePath());
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
        registry.add("app.security.username", () -> "chart");
        registry.add("app.security.password", () -> "chart");
    }

    @Autowired MockMvc mvc;
    @Autowired AppUserRepository userRepo;

    @BeforeEach
    void setUp() {
        AppUser user = TestUsers.create(userRepo, "chart");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new AppUserPrincipal(user), null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("La gráfica se pinta hasta el final, con el script que pide la serie")
    void rendersWholePage() throws Exception {
        CsrfToken csrf = new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "token-de-prueba");

        String html = mvc.perform(get("/chart")
                        .requestAttr(CsrfToken.class.getName(), csrf)
                        .requestAttr("_csrf", csrf))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(html.contains("fetch('/api/portfolio-history?years=' + years)"),
                "falta la llamada que trae la serie");
        for (int years : new int[]{1, 2, 5, 10}) {
            assertTrue(html.contains("data-years=\"" + years + "\""), "falta el botón de " + years + " años");
        }
        assertTrue(html.trim().endsWith("</html>"), "la página se cortó a medias");
    }

    @Test
    @DisplayName("La serie llega con un punto por día de la ventana pedida")
    void historyCoversTheRequestedWindow() throws Exception {
        LocalDate today = LocalDate.now();
        for (int years : new int[]{1, 2, 5, 10}) {
            long days = ChronoUnit.DAYS.between(today.minusYears(years), today) + 1;
            mvc.perform(get("/api/portfolio-history").param("years", String.valueOf(years)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.points.length()").value(days))
                    .andExpect(jsonPath("$.points[0].date").value(today.minusYears(years).toString()));
        }
    }

    @Test
    @DisplayName("Una ventana que la página no ofrece se rechaza")
    void rejectsOtherWindows() throws Exception {
        mvc.perform(get("/api/portfolio-history").param("years", "3"))
                .andExpect(status().isBadRequest());
    }
}
