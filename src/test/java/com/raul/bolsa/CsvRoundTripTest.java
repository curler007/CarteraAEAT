package com.raul.bolsa;

import com.raul.bolsa.domain.AeatGroup;
import com.raul.bolsa.domain.FifoLot;
import com.raul.bolsa.domain.OperationType;
import com.raul.bolsa.domain.SaleRecord;
import com.raul.bolsa.repository.AppUserRepository;
import com.raul.bolsa.repository.FifoLotRepository;
import com.raul.bolsa.repository.OperationRepository;
import com.raul.bolsa.repository.SaleRecordRepository;
import com.raul.bolsa.repository.SplitRepository;
import com.raul.bolsa.service.OperationCsvService;
import com.raul.bolsa.service.OperationService;
import com.raul.bolsa.service.SplitService;
import com.raul.bolsa.web.dto.CsvImportResult;
import com.raul.bolsa.web.dto.ImportMode;
import com.raul.bolsa.web.dto.OperationForm;
import com.raul.bolsa.web.dto.SplitForm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * La prueba de fuego del CSV: exportar la cartera de un usuario, importarla en otro
 * y comprobar que el FIFO reconstruido (lotes + ventas) es idéntico.
 *
 * <p>Si el fichero perdiera un dato — una comisión, el grupo AEAT, un split — el FIFO
 * saldría distinto y el test lo detectaría.
 */
@SpringBootTest
class CsvRoundTripTest {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) throws IOException {
        Path db = Files.createTempDirectory("bolsa-csv-").resolve("test.db");
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + db.toAbsolutePath());
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
        registry.add("app.security.username", () -> "test");
        registry.add("app.security.password", () -> "test");
    }

    @Autowired AppUserRepository userRepo;
    @Autowired OperationCsvService csvService;
    @Autowired OperationService operationService;
    @Autowired SplitService splitService;
    @Autowired OperationRepository operationRepo;
    @Autowired FifoLotRepository fifoLotRepo;
    @Autowired SaleRecordRepository saleRecordRepo;
    @Autowired SplitRepository splitRepo;

    private Long alice;
    private Long bob;

    @BeforeEach
    void setUp() {
        saleRecordRepo.deleteAll();
        fifoLotRepo.deleteAll();
        operationRepo.deleteAll();
        splitRepo.deleteAll();
        userRepo.deleteAll();
        alice = TestUsers.create(userRepo, "alice").getId();
        bob = TestUsers.create(userRepo, "bob").getId();
    }

    /**
     * Cartera con todos los casos delicados: fracciones, varios brokers, los tres grupos
     * AEAT, venta parcial, CANJE, split y notas con caracteres que rompen un CSV ingenuo.
     */
    private void buildPortfolio(Long uid) {
        operationService.save(uid, op(OperationType.BUY, "APPLE", "US0378331005", "Trade Republic",
                "2020-01-15", "2.826455", "501", "1", AeatGroup.GROUP_3, "primera compra"));
        operationService.save(uid, op(OperationType.BUY, "APPLE", "US0378331005", "ING",
                "2020-06-10", "1.5", "300.50", "0.75", AeatGroup.GROUP_3,
                "nota con ; punto y coma y \"comillas\""));
        operationService.save(uid, op(OperationType.SELL, "APPLE", "US0378331005", "MyInvestor",
                "2021-03-01", "3", "800", "1.25", AeatGroup.GROUP_3, null));

        operationService.save(uid, op(OperationType.BUY, "GRIFOLS", "ES0171996087", "ING",
                "2020-02-20", "39.277297", "502", "1", AeatGroup.GROUP_1, null));
        operationService.save(uid, op(OperationType.CANJE, "GRIFOLS", "ES0171996087", "ING",
                "2020-09-01", "5", null, null, AeatGroup.GROUP_1, "ampliación liberada"));

        operationService.save(uid, op(OperationType.BUY, "NVIDIA", "US67066G1040", "Trade Republic",
                "2023-01-10", "10", "1000", "2", AeatGroup.GROUP_2, null));
        SplitForm split = new SplitForm();
        split.setTicker("NVIDIA");
        split.setDate(LocalDate.parse("2024-06-10"));
        split.setRatio(new BigDecimal("10"));
        splitService.save(uid, split);
        operationService.save(uid, op(OperationType.SELL, "NVIDIA", "US67066G1040", "Trade Republic",
                "2024-09-01", "40", "900", "1", AeatGroup.GROUP_2, null));
    }

    @Test
    @DisplayName("Exportar e importar en otra cuenta reproduce el FIFO exactamente")
    void roundTripReproducesFifo() {
        buildPortfolio(alice);

        byte[] csv = csvService.export(alice);
        CsvImportResult result = csvService.importCsv(bob, csv, ImportMode.ADD);

        assertTrue(result.ok(), () -> "La importación falló: " + result.errors());
        assertEquals(7, result.operations(), "Deberían importarse 7 operaciones");
        assertEquals(1, result.splits(), "Debería importarse 1 split");

        assertEquals(lots(alice), lots(bob),
                "Los lotes FIFO de Bob no coinciden con los de Alice tras la ida y vuelta");
        assertEquals(sales(alice), sales(bob),
                "Las ventas de Bob no coinciden con las de Alice tras la ida y vuelta");

        // Un segundo viaje partiendo del CSV de Bob debe dar el mismo fichero: el formato es estable.
        assertEquals(new String(csv, StandardCharsets.UTF_8),
                new String(csvService.export(bob), StandardCharsets.UTF_8),
                "Exportar lo importado no devuelve un fichero idéntico");
    }

    @Test
    @DisplayName("Las notas con ';' y comillas sobreviven al viaje")
    void quotingSurvivesRoundTrip() {
        buildPortfolio(alice);
        csvService.importCsv(bob, csvService.export(alice), ImportMode.ADD);

        String expected = "nota con ; punto y coma y \"comillas\"";
        assertTrue(operationRepo.findByUserId(bob).stream()
                        .anyMatch(o -> expected.equals(o.getNotes())),
                "La nota con separador y comillas no ha sobrevivido al CSV");
    }

    @Test
    @DisplayName("Reemplazar deja la cartera igual que el fichero, sin duplicar")
    void replaceModeRebuildsFromScratch() {
        buildPortfolio(alice);
        byte[] csv = csvService.export(alice);

        // Bob parte de datos propios que no están en el fichero
        operationService.save(bob, op(OperationType.BUY, "REPSOL", "ES0173516115", "ING",
                "2019-01-01", "100", "1000", "1", AeatGroup.GROUP_1, null));

        CsvImportResult result = csvService.importCsv(bob, csv, ImportMode.REPLACE);
        assertTrue(result.ok(), () -> "La importación falló: " + result.errors());

        assertTrue(operationRepo.findByUserId(bob).stream()
                        .noneMatch(o -> "REPSOL".equals(o.getTicker())),
                "REPSOL debería haber desaparecido al reemplazar");
        assertEquals(lots(alice), lots(bob), "Reemplazar no ha reproducido la cartera del fichero");
        assertEquals(sales(alice), sales(bob), "Reemplazar no ha reproducido las ventas del fichero");
    }

    @Test
    @DisplayName("Añadir dos veces el mismo fichero duplica, y no toca al otro usuario")
    void addModeAccumulates() {
        buildPortfolio(alice);
        byte[] csv = csvService.export(alice);

        csvService.importCsv(bob, csv, ImportMode.ADD);
        int afterFirst = operationRepo.findByUserId(bob).size();
        csvService.importCsv(bob, csv, ImportMode.ADD);

        assertEquals(afterFirst * 2, operationRepo.findByUserId(bob).size(),
                "El modo añadir debería acumular");
        assertEquals(7, operationRepo.findByUserId(alice).size(),
                "La cartera de Alice no debe verse afectada por las importaciones de Bob");
    }

    @Test
    @DisplayName("Un fichero inválido se rechaza entero, indicando la línea")
    void invalidFileImportsNothing() {
        buildPortfolio(alice);
        int before = operationRepo.findByUserId(alice).size();

        String bad = OperationCsvService.HEADER + "\n"
                + "05/08/2025;BUY;APPLE;US0378331005;Trade Republic;2,5;501;1;GROUP_3;\n"
                + "no-es-fecha;BUY;APPLE;US0378331005;ING;1;100;1;GROUP_3;\n"
                + "06/08/2025;COMPRA;APPLE;US0378331005;ING;1;100;1;GROUP_3;\n"
                + "07/08/2025;BUY;APPLE;US0378331005;ING;-5;100;1;GROUP_3;\n"
                + "08/08/2025;BUY;APPLE;US0378331005;ING;1;100;1;GRUPO_9;\n";

        CsvImportResult result = csvService.importCsv(
                alice, bad.getBytes(StandardCharsets.UTF_8), ImportMode.ADD);

        assertFalse(result.ok(), "Un fichero con filas inválidas no debería importarse");
        assertEquals(4, result.errors().size(),
                () -> "Se esperaban 4 errores, uno por fila mala: " + result.errors());
        assertTrue(result.errors().get(0).startsWith("Línea 3:"),
                () -> "Los errores deben indicar la línea: " + result.errors());
        assertEquals(before, operationRepo.findByUserId(alice).size(),
                "Un fichero inválido no debe modificar nada, ni siquiera las filas correctas");
    }

    @Test
    @DisplayName("Sin cabecera se avisa en vez de tragarse la primera fila")
    void missingHeaderIsReported() {
        String noHeader = "05/08/2025;BUY;APPLE;US0378331005;Trade Republic;2,5;501;1;GROUP_3;\n";
        CsvImportResult result = csvService.importCsv(
                alice, noHeader.getBytes(StandardCharsets.UTF_8), ImportMode.ADD);

        assertFalse(result.ok());
        assertTrue(result.errors().get(0).contains("cabecera"),
                () -> "Debería avisar de la cabecera que falta: " + result.errors());
    }

    @Test
    @DisplayName("Se aceptan decimales con punto y con coma")
    void acceptsBothDecimalSeparators() {
        String csv = OperationCsvService.HEADER + "\n"
                + "05/08/2025;BUY;APPLE;US0378331005;ING;2,5;500,25;1,5;GROUP_3;coma\n"
                + "06/08/2025;BUY;APPLE;US0378331005;ING;2.5;500.25;1.5;3;punto\n";

        CsvImportResult result = csvService.importCsv(
                alice, csv.getBytes(StandardCharsets.UTF_8), ImportMode.ADD);

        assertTrue(result.ok(), () -> "Debería aceptar ambos separadores: " + result.errors());
        List<FifoLot> lots = fifoLotRepo.findByUserIdAndTickerOrderByPurchaseDateAscIdAsc(alice, "APPLE");
        assertEquals(2, lots.size());
        assertEquals(0, lots.get(0).getInitialCost().compareTo(lots.get(1).getInitialCost()),
                "Las dos filas describen el mismo importe y deben producir el mismo coste");
    }

    // ─── Firmas comparables ──────────────────────────────────────────────────

    private List<String> lots(Long uid) {
        return fifoLotRepo.findByUserId(uid).stream()
                .map(l -> String.join("|", l.getTicker(), l.getAssetName(), l.getBroker(),
                        l.getPurchaseDate().toString(),
                        n(l.getInitialQty()), n(l.getInitialCost()),
                        n(l.getRemainingQty()), n(l.getRemainingCost())))
                .sorted().toList();
    }

    private List<String> sales(Long uid) {
        return saleRecordRepo.findByUserId(uid).stream()
                .map(s -> String.join("|", s.getTicker(), s.getBuyBroker(), s.getSellBroker(),
                        s.getPurchaseDate().toString(), s.getSaleDate().toString(),
                        n(s.getQuantity()), n(s.getCostBasis()), n(s.getProceeds()),
                        n(s.getGainLoss()), s.getAeatGroup().name(), String.valueOf(s.getTaxYear())))
                .sorted(Comparator.naturalOrder()).toList();
    }

    private static String n(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    private static OperationForm op(OperationType type, String ticker, String isin, String broker,
                                    String date, String qty, String total, String commission,
                                    AeatGroup group, String notes) {
        OperationForm f = new OperationForm();
        f.setType(type);
        f.setTicker(ticker);
        f.setAssetName(isin);
        f.setBroker(broker);
        f.setDate(LocalDate.parse(date));
        f.setQuantity(new BigDecimal(qty));
        f.setTotal(total == null ? BigDecimal.ZERO : new BigDecimal(total));
        f.setCommission(commission == null ? BigDecimal.ZERO : new BigDecimal(commission));
        f.setAeatGroup(group);
        f.setNotes(notes);
        return f;
    }
}
