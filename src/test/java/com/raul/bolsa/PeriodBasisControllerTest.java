package com.raul.bolsa;

import com.raul.bolsa.domain.AppUser;
import com.raul.bolsa.repository.AppUserRepository;
import com.raul.bolsa.security.AppUserPrincipal;
import com.raul.bolsa.service.PortfolioValuationService;
import com.raul.bolsa.web.dto.PeriodBaseline;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
class PeriodBasisControllerTest {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) throws IOException {
        Path db = Files.createTempDirectory("bolsa-period-basis-").resolve("test.db");
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + db.toAbsolutePath());
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
        registry.add("app.security.username", () -> "period");
        registry.add("app.security.password", () -> "period");
    }

    @Autowired MockMvc mvc;
    @Autowired AppUserRepository userRepo;
    @MockBean PortfolioValuationService valuation;

    private Long uid;

    @BeforeEach
    void setUp() {
        AppUser user = TestUsers.create(userRepo, "period");
        uid = user.getId();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new AppUserPrincipal(user), null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("El endpoint devuelve week/month/year con campos numéricos y missing")
    void returnsPeriodBaselinesJsonContract() throws Exception {
        Long otherUserId = TestUsers.create(userRepo, "period-other").getId();
        when(valuation.baselines(eq(uid), anyMap())).thenReturn(List.of(
                new PeriodBaseline("week", "2026-09-03",
                        new BigDecimal("1000.50"), new BigDecimal("10.00"), new BigDecimal("20.00"), List.of()),
                new PeriodBaseline("month", "2026-08-10",
                        new BigDecimal("900.00"), new BigDecimal("30.00"), new BigDecimal("0.00"), List.of("IE00AAA")),
                new PeriodBaseline("year", "2025-09-10",
                        new BigDecimal("700.00"), new BigDecimal("200.00"), new BigDecimal("50.00"), List.of())
        ));

        mvc.perform(get("/api/period-basis"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].period").value("week"))
                .andExpect(jsonPath("$[1].period").value("month"))
                .andExpect(jsonPath("$[2].period").value("year"))
                .andExpect(jsonPath("$[0].openingValue").isNumber())
                .andExpect(jsonPath("$[0].boughtAfter").isNumber())
                .andExpect(jsonPath("$[0].soldAfter").isNumber())
                .andExpect(jsonPath("$[1].missing[0]").value("IE00AAA"))
                .andExpect(jsonPath("$[0].at").value("2026-09-03"));

        verify(valuation).baselines(eq(uid), anyMap());
        verify(valuation, never()).baselines(eq(otherUserId), anyMap());
    }
}
