package com.raul.bolsa;

import com.raul.bolsa.domain.AeatGroup;
import com.raul.bolsa.domain.FifoLot;
import com.raul.bolsa.domain.OperationType;
import com.raul.bolsa.domain.SaleRecord;
import com.raul.bolsa.repository.FifoLotRepository;
import com.raul.bolsa.repository.SaleRecordRepository;
import com.raul.bolsa.service.OperationService;
import com.raul.bolsa.service.SplitService;
import com.raul.bolsa.web.dto.OperationForm;
import com.raul.bolsa.web.dto.SplitForm;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Reproduce el estado de una BD de referencia (fixture) replayando todas las operaciones en orden
 * aleatorio y los splits cronológicamente contra una BD vacía vía los servicios reales, y comprueba
 * que el resultado del FIFO (lotes + ventas) es idéntico.
 *
 * El orden aleatorio fuerza que se disparen los recálculos de fechas anteriores,
 * que es el camino más delicado de la lógica.
 *
 * La fixture se genera con {@link FixtureGenerator} y se commitea en el repo.
 * Si existe un {@code bolsa.db} local (datos reales), se usa esa en vez de la fixture.
 * La BD de origen no se modifica: se copia a un directorio temporal.
 */
@SpringBootTest
class ReplayConsistencyTest {

    private static final long SHUFFLE_SEED = 42L;
    private static final Path LOCAL_DB = Path.of("bolsa.db");
    private static final Path FIXTURE_DB = Path.of("src/test/resources/fixture.db");

    private static Path refDb;
    private static Path targetDb;
    private static Path tempDir;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) throws Exception {
        tempDir = Files.createTempDirectory("bolsa-replay-");
        refDb = tempDir.resolve("ref.db");
        targetDb = tempDir.resolve("target.db");
        Path source = Files.exists(LOCAL_DB) ? LOCAL_DB : FIXTURE_DB;
        Files.copy(source, refDb, StandardCopyOption.REPLACE_EXISTING);

        registry.add("spring.datasource.url",
                () -> "jdbc:sqlite:" + targetDb.toAbsolutePath());
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
        registry.add("app.security.username", () -> "test");
        registry.add("app.security.password", () -> "test");
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (tempDir != null) {
            try (var stream = Files.walk(tempDir)) {
                stream.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    @Autowired OperationService operationService;
    @Autowired SplitService splitService;
    @Autowired FifoLotRepository fifoLotRepo;
    @Autowired SaleRecordRepository saleRecordRepo;

    @Test
    void replayProducesSameFifoState() throws Exception {
        List<OpRow> ops;
        List<SplitRow> splits;
        List<LotSig> expectedLots;
        List<SaleSig> expectedSales;
        try (Connection ref = DriverManager.getConnection("jdbc:sqlite:" + refDb)) {
            ops = loadOps(ref);
            splits = loadSplits(ref);
            expectedLots = loadExpectedLots(ref);
            expectedSales = loadExpectedSales(ref);
        }

        List<OpRow> shuffled = new ArrayList<>(ops);
        Collections.shuffle(shuffled, new Random(SHUFFLE_SEED));

        for (OpRow op : shuffled) {
            operationService.save(op.toForm());
        }
        splits.stream()
                .sorted(Comparator.comparing(SplitRow::date).thenComparingLong(SplitRow::id))
                .forEach(s -> splitService.save(s.toForm()));

        List<LotSig> actualLots = fifoLotRepo.findAll().stream()
                .map(LotSig::of)
                .sorted(LotSig.ORDER)
                .toList();
        List<SaleSig> actualSales = saleRecordRepo.findAll().stream()
                .map(SaleSig::of)
                .sorted(SaleSig.ORDER)
                .toList();

        expectedLots = expectedLots.stream().sorted(LotSig.ORDER).toList();
        expectedSales = expectedSales.stream().sorted(SaleSig.ORDER).toList();

        // Sanidad: si la BD de referencia está vacía, el test no compara nada y pasaría en falso.
        assertFalse(expectedLots.isEmpty(), "bolsa.db no tiene lotes — el test no estaría comparando nada");
        assertFalse(expectedSales.isEmpty(), "bolsa.db no tiene ventas — el test no estaría comparando nada");

        List<String> diffs = new ArrayList<>();
        diff("FifoLot", expectedLots, actualLots, diffs);
        diff("SaleRecord", expectedSales, actualSales, diffs);

        if (!diffs.isEmpty()) {
            fail("El replay produjo un FIFO distinto al de bolsa.db:\n\n"
                    + String.join("\n", diffs)
                    + "\n\n(" + diffs.size() + " diferencias)");
        }
    }

    // ─── Comparación ─────────────────────────────────────────────────────────

    private static <T> void diff(String label, List<T> expected, List<T> actual, List<String> out) {
        if (expected.size() != actual.size()) {
            out.add(String.format("%s: cantidad distinta — esperado=%d, obtenido=%d",
                    label, expected.size(), actual.size()));
        }
        int n = Math.min(expected.size(), actual.size());
        for (int i = 0; i < n; i++) {
            if (!expected.get(i).equals(actual.get(i))) {
                out.add(String.format("%s[%d]:%n  esperado = %s%n  obtenido = %s",
                        label, i, expected.get(i), actual.get(i)));
            }
        }
        for (int i = n; i < expected.size(); i++) {
            out.add(String.format("%s[%d] falta en replay: %s", label, i, expected.get(i)));
        }
        for (int i = n; i < actual.size(); i++) {
            out.add(String.format("%s[%d] sobra en replay: %s", label, i, actual.get(i)));
        }
    }

    // ─── Lectura de la BD de referencia (JDBC directo) ───────────────────────

    private static List<OpRow> loadOps(Connection ref) throws Exception {
        List<OpRow> out = new ArrayList<>();
        String sql = "SELECT id, date, broker, type, ticker, asset_name, quantity, " +
                "total, commission, aeat_group, notes FROM operations";
        try (Statement s = ref.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                out.add(new OpRow(
                        rs.getLong("id"),
                        parseDate(rs.getString("date")),
                        rs.getString("broker"),
                        OperationType.valueOf(rs.getString("type")),
                        rs.getString("ticker"),
                        rs.getString("asset_name"),
                        rs.getBigDecimal("quantity"),
                        rs.getBigDecimal("total"),
                        rs.getBigDecimal("commission"),
                        AeatGroup.valueOf(rs.getString("aeat_group")),
                        rs.getString("notes")
                ));
            }
        }
        return out;
    }

    private static List<SplitRow> loadSplits(Connection ref) throws Exception {
        List<SplitRow> out = new ArrayList<>();
        try (Statement s = ref.createStatement();
             ResultSet rs = s.executeQuery("SELECT id, date, ticker, ratio FROM splits")) {
            while (rs.next()) {
                out.add(new SplitRow(
                        rs.getLong("id"),
                        parseDate(rs.getString("date")),
                        rs.getString("ticker"),
                        rs.getBigDecimal("ratio")
                ));
            }
        }
        return out;
    }

    private static List<LotSig> loadExpectedLots(Connection ref) throws Exception {
        List<LotSig> out = new ArrayList<>();
        String sql = "SELECT ticker, broker, purchase_date, initial_qty, initial_cost, " +
                "remaining_qty, remaining_cost FROM fifo_lots";
        try (Statement s = ref.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                out.add(new LotSig(
                        rs.getString("ticker"),
                        rs.getString("broker"),
                        parseDate(rs.getString("purchase_date")),
                        norm(rs.getBigDecimal("initial_qty")),
                        norm(rs.getBigDecimal("initial_cost")),
                        norm(rs.getBigDecimal("remaining_qty")),
                        norm(rs.getBigDecimal("remaining_cost"))
                ));
            }
        }
        return out;
    }

    private static List<SaleSig> loadExpectedSales(Connection ref) throws Exception {
        List<SaleSig> out = new ArrayList<>();
        String sql = "SELECT ticker, buy_broker, sell_broker, purchase_date, sale_date, " +
                "quantity, cost_basis, proceeds, gain_loss, aeat_group, tax_year FROM sale_records";
        try (Statement s = ref.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                out.add(new SaleSig(
                        rs.getString("ticker"),
                        rs.getString("buy_broker"),
                        rs.getString("sell_broker"),
                        parseDate(rs.getString("purchase_date")),
                        parseDate(rs.getString("sale_date")),
                        norm(rs.getBigDecimal("quantity")),
                        norm(rs.getBigDecimal("cost_basis")),
                        norm(rs.getBigDecimal("proceeds")),
                        norm(rs.getBigDecimal("gain_loss")),
                        AeatGroup.valueOf(rs.getString("aeat_group")),
                        rs.getInt("tax_year")
                ));
            }
        }
        return out;
    }

    /** Acepta tanto "yyyy-MM-dd HH:mm:ss.S" (canónico) como "yyyy-MM-dd" puro. */
    private static LocalDate parseDate(String raw) {
        return LocalDate.parse(raw.substring(0, 10));
    }

    /** Normaliza un BigDecimal para que dos valores numéricamente iguales sean {@code equals}. */
    private static BigDecimal norm(BigDecimal b) {
        if (b == null) return null;
        BigDecimal stripped = b.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }

    // ─── Tipos auxiliares ────────────────────────────────────────────────────

    private record OpRow(
            long id, LocalDate date, String broker, OperationType type,
            String ticker, String assetName, BigDecimal quantity, BigDecimal total,
            BigDecimal commission, AeatGroup aeatGroup, String notes
    ) {
        OperationForm toForm() {
            OperationForm f = new OperationForm();
            f.setDate(date);
            f.setBroker(broker);
            f.setType(type);
            f.setTicker(ticker);
            f.setAssetName(assetName);
            f.setQuantity(quantity);
            f.setTotal(total);
            f.setCommission(commission != null ? commission : BigDecimal.ZERO);
            f.setAeatGroup(aeatGroup);
            f.setNotes(notes);
            return f;
        }
    }

    private record SplitRow(long id, LocalDate date, String ticker, BigDecimal ratio) {
        SplitForm toForm() {
            SplitForm f = new SplitForm();
            f.setDate(date);
            f.setTicker(ticker);
            f.setRatio(ratio);
            return f;
        }
    }

    private record LotSig(
            String ticker, String broker, LocalDate purchaseDate,
            BigDecimal initialQty, BigDecimal initialCost,
            BigDecimal remainingQty, BigDecimal remainingCost
    ) {
        static LotSig of(FifoLot l) {
            return new LotSig(l.getTicker(), l.getBroker(), l.getPurchaseDate(),
                    norm(l.getInitialQty()), norm(l.getInitialCost()),
                    norm(l.getRemainingQty()), norm(l.getRemainingCost()));
        }
        static final Comparator<LotSig> ORDER = Comparator
                .comparing(LotSig::ticker)
                .thenComparing(LotSig::purchaseDate)
                .thenComparing(LotSig::broker)
                .thenComparing(LotSig::initialQty)
                .thenComparing(LotSig::initialCost);
    }

    private record SaleSig(
            String ticker, String buyBroker, String sellBroker,
            LocalDate purchaseDate, LocalDate saleDate,
            BigDecimal quantity, BigDecimal costBasis,
            BigDecimal proceeds, BigDecimal gainLoss,
            AeatGroup aeatGroup, int taxYear
    ) {
        static SaleSig of(SaleRecord r) {
            return new SaleSig(r.getTicker(), r.getBuyBroker(), r.getSellBroker(),
                    r.getPurchaseDate(), r.getSaleDate(),
                    norm(r.getQuantity()), norm(r.getCostBasis()),
                    norm(r.getProceeds()), norm(r.getGainLoss()),
                    r.getAeatGroup(), r.getTaxYear());
        }
        static final Comparator<SaleSig> ORDER = Comparator
                .comparing(SaleSig::ticker)
                .thenComparing(SaleSig::saleDate)
                .thenComparing(SaleSig::purchaseDate)
                .thenComparing(SaleSig::quantity)
                .thenComparing(SaleSig::buyBroker);
    }
}
