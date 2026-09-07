package com.raul.bolsa;

import com.raul.bolsa.domain.AeatGroup;
import com.raul.bolsa.domain.AppUser;
import com.raul.bolsa.domain.IsinTwin;
import com.raul.bolsa.domain.OperationType;
import com.raul.bolsa.repository.AppUserRepository;
import com.raul.bolsa.repository.IsinTwinRepository;
import com.raul.bolsa.security.AppUserPrincipal;
import com.raul.bolsa.service.IsinTwinService;
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
 * La pantalla de gemelos y, sobre todo, que lo ya resuelto no se vuelva a preguntar.
 *
 * <p>El test no toca la red a propósito: siembra un símbolo que Yahoo jamás devolvería y comprueba
 * que sobrevive. Si la caché no funcionara, la consulta real lo machacaría y el test fallaría —
 * además de tardar unos segundos.
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
class IsinTwinsPageTest {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) throws IOException {
        Path db = Files.createTempDirectory("bolsa-twins-").resolve("test.db");
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + db.toAbsolutePath());
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
        registry.add("app.security.username", () -> "twins");
        registry.add("app.security.password", () -> "twins");
    }

    @Autowired MockMvc mvc;
    @Autowired AppUserRepository userRepo;
    @Autowired IsinTwinRepository twinRepo;
    @Autowired OperationService operationService;
    @Autowired IsinTwinService twinService;

    private Long uid;

    @BeforeEach
    void setUp() {
        AppUser user = TestUsers.create(userRepo, "twins");
        uid = user.getId();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new AppUserPrincipal(user), null, List.of()));

        OperationForm f = new OperationForm();
        f.setDate(LocalDate.parse("2024-01-10"));
        f.setType(OperationType.BUY);
        f.setTicker("IE00BMVB5P51");
        f.setAssetName("IE00BMVB5P51");
        f.setBroker("MyInvestor");
        f.setQuantity(new BigDecimal("100"));
        f.setTotal(new BigDecimal("1000"));
        f.setCommission(BigDecimal.ZERO);
        f.setAeatGroup(AeatGroup.GROUP_2);
        operationService.save(uid, f);

        IsinTwin seeded = new IsinTwin();
        seeded.setUserId(uid);
        seeded.setIsin("IE00BMVB5P51");
        seeded.setTwin("V60A.AS");
        seeded.setResolvedSymbol("SIMBOLO-DE-PRUEBA");
        seeded.setHistoryFrom(LocalDate.parse("2021-09-07"));
        seeded.setCheckedAt(LocalDate.parse("2026-01-01"));
        twinRepo.save(seeded);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Un ISIN ya resuelto no se vuelve a consultar a Yahoo")
    void resolvedIsinIsNotCheckedAgain() {
        List<IsinTwin> statuses = twinService.statuses(uid);

        assertEquals(1, statuses.size());
        assertEquals("SIMBOLO-DE-PRUEBA", statuses.get(0).getResolvedSymbol(),
                "lo ya resuelto debe salir de la base, no de una consulta nueva");
        assertEquals(LocalDate.parse("2026-01-01"), statuses.get(0).getCheckedAt(),
                "si se hubiera vuelto a comprobar, la fecha sería la de hoy");
    }

    @Test
    @DisplayName("La pantalla de gemelos pinta el ISIN con su símbolo y su gemelo")
    void rendersTwinsPage() throws Exception {
        CsrfToken csrf = new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "token-de-prueba");

        String html = mvc.perform(get("/gemelos")
                        .requestAttr(CsrfToken.class.getName(), csrf)
                        .requestAttr("_csrf", csrf))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(html.contains("IE00BMVB5P51"), "falta el ISIN");
        assertTrue(html.contains("SIMBOLO-DE-PRUEBA"), "falta el símbolo con el que cotiza");
        assertTrue(html.contains("V60A.AS"), "el gemelo debe venir escrito en su casilla");
        assertTrue(html.contains("07/09/2021"), "falta desde cuándo hay histórico");
    }
}
