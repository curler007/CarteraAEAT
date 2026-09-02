package com.raul.bolsa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.raul.bolsa.domain.AeatGroup;
import com.raul.bolsa.domain.OperationType;
import com.raul.bolsa.repository.AppUserRepository;
import com.raul.bolsa.repository.FifoLotRepository;
import com.raul.bolsa.repository.OperationRepository;
import com.raul.bolsa.repository.SaleRecordRepository;
import com.raul.bolsa.repository.SplitRepository;
import com.raul.bolsa.service.OperationService;
import com.raul.bolsa.service.QuoteService;
import com.raul.bolsa.service.SplitDetectionService;
import com.raul.bolsa.service.SplitService;
import com.raul.bolsa.web.dto.DetectedSplit;
import com.raul.bolsa.web.dto.OperationForm;
import com.raul.bolsa.web.dto.SplitForm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * La detección solo debe proponer splits que caigan en una fecha con posición viva.
 *
 * <p>El caso importante es el hueco: un valor comprado, vendido entero y recomprado más tarde
 * tiene tramos en los que un split no toca ningún lote y por tanto no afecta al FIFO.
 * La respuesta de Yahoo se sustituye por un mock para que el test no dependa de la red.
 */
@SpringBootTest
class SplitDetectionTest {

    private static final String TICKER = "NVDA";
    private static final String ISIN = "US67066G1040";

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) throws IOException {
        Path db = Files.createTempDirectory("bolsa-detect-").resolve("test.db");
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + db.toAbsolutePath());
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
        registry.add("app.security.username", () -> "test");
        registry.add("app.security.password", () -> "test");
    }

    @MockBean QuoteService quoteService;

    @Autowired SplitDetectionService detectionService;
    @Autowired OperationService operationService;
    @Autowired SplitService splitService;
    @Autowired AppUserRepository userRepo;
    @Autowired OperationRepository operationRepo;
    @Autowired FifoLotRepository fifoLotRepo;
    @Autowired SaleRecordRepository saleRecordRepo;
    @Autowired SplitRepository splitRepo;

    private Long alice;

    @BeforeEach
    void setUp() throws Exception {
        saleRecordRepo.deleteAll();
        fifoLotRepo.deleteAll();
        operationRepo.deleteAll();
        splitRepo.deleteAll();
        userRepo.deleteAll();
        detectionService.clearCache();

        alice = TestUsers.create(userRepo, "alice").getId();

        when(quoteService.candidateSymbols(ISIN)).thenReturn(List.of(TICKER));
        when(quoteService.fetchChartResult(eq(TICKER), any())).thenReturn(Optional.of(chart(
                split("2019-06-01", 2, 1),    // antes de la primera compra
                split("2020-01-01", 3, 1),    // el mismo día de la primera compra
                split("2023-06-01", 2, 1),    // en el hueco: vendido todo y aún sin recomprar
                split("2025-06-01", 10, 1))));  // con posición viva: es el único que afecta
    }

    @Test
    @DisplayName("Solo se sugieren los splits que caen con posición viva del valor")
    void suggestsOnlySplitsAffectingOpenPosition() {
        operationService.save(alice, buy("2020-01-01", "100", "1000"));
        operationService.save(alice, sell("2022-01-01", "100", "3000"));  // cierra la posición
        operationService.save(alice, buy("2024-01-01", "50", "2000"));    // la reabre

        List<DetectedSplit> detected = detectionService.detect(alice);

        assertEquals(List.of(LocalDate.parse("2025-06-01")),
                detected.stream().map(DetectedSplit::date).toList(),
                "Solo el split de 2025 cae con acciones en cartera. Detectados: " + dates(detected));

        DetectedSplit d = detected.get(0);
        assertEquals(0, new BigDecimal("10").compareTo(d.ratio()), "Ratio 10:1 debería dar factor 10");
        assertEquals(0, new BigDecimal("50").compareTo(d.position()),
                "En 2025 tenía las 50 acciones de la recompra de 2024");
        assertEquals(TICKER, d.ticker());
        assertFalse(d.likelyScrip(), "Un 10:1 no es un dividendo en acciones");
    }

    @Test
    @DisplayName("Un split ya registrado deja de sugerirse, y los demás siguen apareciendo")
    void doesNotSuggestRegisteredSplits() {
        // Comprada en 2020 y nunca vendida: tiene posición viva en 2023 y en 2025
        operationService.save(alice, buy("2020-01-01", "100", "1000"));
        assertEquals(List.of(LocalDate.parse("2025-06-01"), LocalDate.parse("2023-06-01")),
                detectionService.detect(alice).stream().map(DetectedSplit::date).toList(),
                "Con la posición abierta desde 2020 faltan los splits de 2023 y 2025");

        register("2025-06-01", "10");

        assertEquals(List.of(LocalDate.parse("2023-06-01")),
                detectionService.detect(alice).stream().map(DetectedSplit::date).toList(),
                "El split recién dado de alta no debería volver a sugerirse, "
                        + "pero el de 2023 sí sigue pendiente");
    }

    @Test
    @DisplayName("La posición se mide en acciones de la fecha del split, ya ajustadas por splits previos")
    void positionAccountsForEarlierRegisteredSplits() {
        operationService.save(alice, buy("2020-01-01", "100", "1000"));

        // Split intermedio registrado a mano: multiplica por 4 las 100 acciones de 2020
        register("2021-07-20", "4");

        // Se vende la mitad de la posición post-split: quedan 200
        operationService.save(alice, sell("2022-01-01", "200", "5000"));

        List<DetectedSplit> detected = detectionService.detect(alice);
        DetectedSplit d2025 = detected.stream()
                .filter(d -> d.date().equals(LocalDate.parse("2025-06-01")))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Sin aplicar el split de 2021 el saldo saldría negativo (100 - 200) y el "
                                + "split de 2025 se descartaría por error. Detectados: " + dates(detected)));
        assertEquals(0, new BigDecimal("200").compareTo(d2025.position()),
                "La posición debe ir en acciones post-split. Obtenida: " + d2025.position());
    }

    /** Da de alta un split como si el usuario lo hubiera aceptado. */
    private void register(String date, String ratio) {
        SplitForm form = new SplitForm();
        form.setTicker(TICKER);
        form.setDate(LocalDate.parse(date));
        form.setRatio(new BigDecimal(ratio));
        splitService.save(alice, form);
    }

    private static String dates(List<DetectedSplit> detected) {
        return detected.stream().map(d -> d.date().toString()).collect(Collectors.joining(", "));
    }

    /** Respuesta de Yahoo recortada a lo que lee el parser: {@code chart.result[0].events.splits}. */
    private static JsonNode chart(String... splits) throws Exception {
        return new ObjectMapper().readTree(
                "{\"events\":{\"splits\":{" + String.join(",", splits) + "}}}");
    }

    private static String split(String date, int numerator, int denominator) {
        long epoch = LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        return "\"" + epoch + "\":{"
                + "\"date\":" + epoch + ","
                + "\"numerator\":" + numerator + ".0,"
                + "\"denominator\":" + denominator + ".0,"
                + "\"splitRatio\":\"" + numerator + ":" + denominator + "\"}";
    }

    private static OperationForm buy(String date, String qty, String total) {
        return form(OperationType.BUY, date, qty, total);
    }

    private static OperationForm sell(String date, String qty, String total) {
        return form(OperationType.SELL, date, qty, total);
    }

    private static OperationForm form(OperationType type, String date, String qty, String total) {
        OperationForm f = new OperationForm();
        f.setType(type);
        f.setTicker(TICKER);
        f.setAssetName(ISIN);
        f.setBroker("TestBroker");
        f.setDate(LocalDate.parse(date));
        f.setQuantity(new BigDecimal(qty));
        f.setTotal(new BigDecimal(total));
        f.setCommission(BigDecimal.ZERO);
        f.setAeatGroup(AeatGroup.GROUP_2);
        return f;
    }
}
