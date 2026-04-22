package com.raul.bolsa;

import com.raul.bolsa.domain.AeatGroup;
import com.raul.bolsa.domain.OperationType;
import com.raul.bolsa.service.OperationService;
import com.raul.bolsa.service.SplitService;
import com.raul.bolsa.web.dto.OperationForm;
import com.raul.bolsa.web.dto.SplitForm;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static com.raul.bolsa.domain.AeatGroup.GROUP_1;
import static com.raul.bolsa.domain.AeatGroup.GROUP_2;
import static com.raul.bolsa.domain.AeatGroup.GROUP_3;
import static com.raul.bolsa.domain.OperationType.BUY;
import static com.raul.bolsa.domain.OperationType.CANJE;
import static com.raul.bolsa.domain.OperationType.SELL;

/**
 * Genera {@code src/test/resources/fixture.db} con datos sintéticos que ejercitan los mismos
 * casos que {@code bolsa.db}: FIFO básico, multi-broker global, CANJE, SPLIT y ventas pendientes.
 *
 * <p>No se ejecuta por defecto. Para regenerar la fixture:
 * <pre>JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=FixtureGenerator -Dgen.fixture=true</pre>
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "gen.fixture", matches = "true")
class FixtureGenerator {

    private static final Path FIXTURE = Path.of("src/test/resources/fixture.db");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) throws Exception {
        Files.createDirectories(FIXTURE.getParent());
        Files.deleteIfExists(FIXTURE);
        registry.add("spring.datasource.url",
                () -> "jdbc:sqlite:" + FIXTURE.toAbsolutePath());
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
        registry.add("app.security.username", () -> "test");
        registry.add("app.security.password", () -> "test");
    }

    @Autowired OperationService operationService;
    @Autowired SplitService splitService;

    @Test
    void generate() {
        OPS.forEach(o -> operationService.save(o.toForm()));
        SPLITS.forEach(s -> splitService.save(s.toForm()));
    }

    // ─── Datos sintéticos ────────────────────────────────────────────────────
    // Tickers ficticios (AAAA..FFFF). Cada bloque cubre un caso distinto del FIFO.

    private static final List<OpData> OPS = List.of(
            // AAAA — FIFO básico: una venta consume dos lotes parcialmente.
            new OpData("2025-01-10", "TradeRep", BUY,  "AAAA", "Asset Alpha",   "10", "100",  "1", GROUP_2),
            new OpData("2025-02-15", "TradeRep", BUY,  "AAAA", "Asset Alpha",   "20", "250",  "1", GROUP_2),
            new OpData("2025-03-20", "TradeRep", SELL, "AAAA", "Asset Alpha",   "15", "200",  "1", GROUP_2),

            // BBBB — FIFO global entre tres brokers.
            new OpData("2025-01-05", "IBKR",     BUY,  "BBBB", "Asset Bravo",   "50", "500",  "2", GROUP_1),
            new OpData("2025-02-10", "XTB",      BUY,  "BBBB", "Asset Bravo",   "30", "330",  "2", GROUP_1),
            new OpData("2025-03-15", "IBKR",     BUY,  "BBBB", "Asset Bravo",   "20", "240",  "2", GROUP_1),
            new OpData("2025-04-01", "XTB",      SELL, "BBBB", "Asset Bravo",   "70", "800",  "3", GROUP_1),

            // CCCC — CANJE redistribuye coste proporcionalmente antes de una venta.
            new OpData("2025-01-20", "TradeRep", BUY,   "CCCC", "Asset Charlie", "100", "1000", "2", GROUP_2),
            new OpData("2025-03-10", "TradeRep", CANJE, "CCCC", "Asset Charlie", "10",  "0",    "0", GROUP_2),
            new OpData("2025-04-05", "TradeRep", SELL,  "CCCC", "Asset Charlie", "55",  "650",  "2", GROUP_2),

            // DDDD — SPLIT 2:1 multiplica la cantidad antes de la venta.
            new OpData("2025-01-15", "TradeRep", BUY,  "DDDD", "Asset Delta",   "10", "100",  "1", GROUP_3),
            new OpData("2025-05-01", "TradeRep", SELL, "DDDD", "Asset Delta",   "20", "300",  "1", GROUP_3),

            // EEEE — compra y venta parcial sin complicaciones.
            new OpData("2025-01-01", "TradeRep", BUY,  "EEEE", "Asset Echo",    "10", "100",  "1", GROUP_2),
            new OpData("2025-02-20", "TradeRep", SELL, "EEEE", "Asset Echo",    "5",  "50",   "1", GROUP_2),

            // FFFF — la venta excede al stock: queda pendingQty permanente.
            new OpData("2025-01-01", "TradeRep", BUY,  "FFFF", "Asset Foxtrot", "10", "100",  "1", GROUP_2),
            new OpData("2025-06-01", "TradeRep", SELL, "FFFF", "Asset Foxtrot", "15", "200",  "1", GROUP_2)
    );

    private static final List<SplitData> SPLITS = List.of(
            new SplitData("2025-03-01", "DDDD", "2")
    );

    private record OpData(
            String date, String broker, OperationType type, String ticker, String assetName,
            String quantity, String total, String commission, AeatGroup aeatGroup
    ) {
        OperationForm toForm() {
            OperationForm f = new OperationForm();
            f.setDate(LocalDate.parse(date));
            f.setBroker(broker);
            f.setType(type);
            f.setTicker(ticker);
            f.setAssetName(assetName);
            f.setQuantity(new BigDecimal(quantity));
            f.setTotal(new BigDecimal(total));
            f.setCommission(new BigDecimal(commission));
            f.setAeatGroup(aeatGroup);
            return f;
        }
    }

    private record SplitData(String date, String ticker, String ratio) {
        SplitForm toForm() {
            SplitForm f = new SplitForm();
            f.setDate(LocalDate.parse(date));
            f.setTicker(ticker);
            f.setRatio(new BigDecimal(ratio));
            return f;
        }
    }
}
