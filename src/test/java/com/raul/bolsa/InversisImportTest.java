package com.raul.bolsa;

import com.raul.bolsa.domain.AeatGroup;
import com.raul.bolsa.domain.FifoLot;
import com.raul.bolsa.domain.Operation;
import com.raul.bolsa.domain.OperationType;
import com.raul.bolsa.domain.SaleRecord;
import com.raul.bolsa.repository.AppUserRepository;
import com.raul.bolsa.repository.FifoLotRepository;
import com.raul.bolsa.repository.OperationRepository;
import com.raul.bolsa.repository.SaleRecordRepository;
import com.raul.bolsa.service.EcbFxRateService;
import com.raul.bolsa.service.FifoService;
import com.raul.bolsa.service.InversisXlsService;
import com.raul.bolsa.service.OperationCsvService;
import com.raul.bolsa.web.dto.CsvImportResult;
import com.raul.bolsa.web.dto.ImportMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Importación del extracto de movimientos de MyInvestor (Inversis).
 *
 * <p>El fichero de ejemplo reproduce la forma real del que sirve el gestor —tabla HTML en
 * ISO-8859-1, una fila por orden, once columnas— pero con fondos y cifras inventadas, elegidas
 * para que las cuentas del FIFO salgan redondas:
 *
 * <pre>
 *   10/01/2024  compra A     100 tít.   1000 €   (partida en dos órdenes de 500 €)
 *   10/02/2024  compra A      50 tít.    600 €
 *   03/06/2024  traspaso A → 120 tít.   1500 €   consume los 100 del primer lote y 20 del segundo
 *   05/06/2024  traspaso → B  75 tít.   1500 €   hereda 1240 € de coste y las dos fechas
 *   10/09/2024  compra C      10 tít.    125 USD
 *   15/01/2025  compra C      10 tít.    125 USD  el mismo fondo, ya con otro nombre
 *   20/03/2025  reembolso B   30 tít.    700 €   único movimiento que tributa
 * </pre>
 *
 * <p>El tipo de cambio se fija a 1,25 USD/€ en vez de consultar al BCE: el test comprueba la
 * conversión, no la red.
 */
@SpringBootTest
class InversisImportTest {

    private static final Path FIXTURE = Path.of("src/test/resources/inversis-ejemplo.xls");

    private static final String FUND_A = "IE0000000001";
    private static final String FUND_B = "IE0000000002";
    private static final String FUND_C = "IE0000000003";

    /** El ticker es el nombre del fondo, que es la columna "Valor" del extracto. */
    private static final String NAME_A = "FONDO A INDEX P ACC EUR";
    private static final String NAME_B = "FONDO B INDEX P ACC EUR";
    private static final String NAME_C = "GESTORA FONDO C IDX P AC USD";

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) throws IOException {
        Path db = Files.createTempDirectory("bolsa-inv-").resolve("test.db");
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + db.toAbsolutePath());
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
        registry.add("app.security.username", () -> "test");
        registry.add("app.security.password", () -> "test");
    }

    /** El BCE no se consulta en los tests: un dólar y cuarto por euro, fijo. */
    @MockBean EcbFxRateService fxRates;

    @Autowired OperationCsvService csvService;
    @Autowired FifoService fifoService;
    @Autowired AppUserRepository userRepo;
    @Autowired OperationRepository operationRepo;
    @Autowired FifoLotRepository fifoLotRepo;
    @Autowired SaleRecordRepository saleRecordRepo;

    private Long alice;

    @BeforeEach
    void setUp() {
        saleRecordRepo.deleteAll();
        fifoLotRepo.deleteAll();
        operationRepo.deleteAll();
        userRepo.deleteAll();
        alice = TestUsers.create(userRepo, "alice-inversis").getId();

        Mockito.when(fxRates.toEur(Mockito.any(), Mockito.anyString(), Mockito.any()))
                .thenAnswer(call -> {
                    BigDecimal amount = call.getArgument(0);
                    String currency = call.getArgument(1);
                    return "EUR".equals(currency)
                            ? Optional.of(amount)
                            : Optional.of(amount.divide(new BigDecimal("1.25"), 6, RoundingMode.HALF_UP));
                });
    }

    private CsvImportResult importFixture(ImportMode mode) throws IOException {
        return csvService.importCsv(alice, Files.readAllBytes(FIXTURE), mode);
    }

    @Test
    @DisplayName("Reconoce el fichero por sus cabeceras, aunque la extensión diga .xls")
    void detectsFormat() throws IOException {
        assertTrue(InversisXlsService.matches(Files.readAllBytes(FIXTURE)));
        assertTrue(InversisXlsService.matches(
                "<table><tr><th>ISIN</th><th>Liquidación</th><th>Importe neto</th></tr></table>"
                        .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)));
        assertTrue(!InversisXlsService.matches("Fecha;Tipo;Ticker".getBytes()));
    }

    @Test
    @DisplayName("Las órdenes del mismo fondo, día y tipo se agrupan en una sola operación")
    void aggregatesOrdersOfTheSameDay() throws IOException {
        CsvImportResult result = importFixture(ImportMode.ADD);

        assertEquals(List.of(), result.errors());
        assertEquals(OperationCsvService.FORMAT_INVERSIS, result.format());
        // 4 compras + 1 salida + 1 entrada + 1 reembolso; las dos órdenes del 10/01 van juntas
        assertEquals(7, result.operations());
        assertEquals(1, result.ignoredCount(), "la comisión de gestión no mueve posiciones");

        Operation first = operations(NAME_A).get(0);
        assertEquals(LocalDate.of(2024, 1, 10), first.getDate());
        assertEquals(0, new BigDecimal("100").compareTo(first.getQuantity()));
        assertEquals(0, new BigDecimal("1000").compareTo(first.getTotal()));
        assertTrue(first.getNotes().contains("2 órdenes"), first.getNotes());
        assertEquals(InversisXlsService.BROKER, first.getBroker());
        assertEquals(AeatGroup.GROUP_2, first.getAeatGroup(), "ISIN irlandés → mercado europeo");
    }

    @Test
    @DisplayName("El traspaso se importa como traspaso, no como compraventa")
    void mapsMovementTypes() throws IOException {
        importFixture(ImportMode.ADD);

        assertEquals(OperationType.TRASPASO_OUT, single(NAME_A, OperationType.TRASPASO_OUT).getType());
        assertEquals(OperationType.TRASPASO_IN, single(NAME_B, OperationType.TRASPASO_IN).getType());

        // Las dos patas quedan emparejadas: es lo que permite que el coste viaje entre fondos
        String out = single(NAME_A, OperationType.TRASPASO_OUT).getTransferId();
        assertNotNull(out);
        assertEquals(out, single(NAME_B, OperationType.TRASPASO_IN).getTransferId());
    }

    @Test
    @DisplayName("Solo el reembolso llega a la declaración; el traspaso no genera ganancia")
    void onlyRedemptionIsTaxable() throws IOException {
        importFixture(ImportMode.ADD);

        List<SaleRecord> sales = saleRecordRepo.findByUserId(alice);
        assertEquals(1, sales.size(), "el traspaso no puede dejar rastro fiscal");

        SaleRecord sale = sales.get(0);
        assertEquals(NAME_B, sale.getTicker());
        assertEquals(LocalDate.of(2025, 3, 20), sale.getSaleDate());
        // Hereda la fecha del fondo de origen, no la del traspaso: la antigüedad viaja con él
        assertEquals(LocalDate.of(2024, 1, 10), sale.getPurchaseDate());
        // 30 de las 62,5 participaciones que traían 1000 € de coste
        assertEquals(0, new BigDecimal("480.00").compareTo(scale(sale.getCostBasis())));
        assertEquals(0, new BigDecimal("700.00").compareTo(scale(sale.getProceeds())));
        assertEquals(0, new BigDecimal("220.00").compareTo(scale(sale.getGainLoss())));
    }

    @Test
    @DisplayName("El traspaso no crea ni destruye coste")
    void transferPreservesCost() throws IOException {
        importFixture(ImportMode.ADD);

        // 1000 + 600 aportados al fondo A, más dos compras de 100 € (125 USD) al fondo C
        BigDecimal contributed = operationRepo.findByUserId(alice).stream()
                .filter(o -> o.getType() == OperationType.BUY)
                .map(Operation::getTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, new BigDecimal("1800.00").compareTo(scale(contributed)));

        BigDecimal openCost = fifoLotRepo.findByUserId(alice).stream()
                .map(FifoLot::getRemainingCost).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal soldCost = saleRecordRepo.findByUserId(alice).stream()
                .map(SaleRecord::getCostBasis).reduce(BigDecimal.ZERO, BigDecimal::add);

        assertEquals(0, contributed.compareTo(openCost.add(soldCost)),
                "coste vivo " + openCost + " + coste vendido " + soldCost + " ≠ " + contributed);
    }

    @Test
    @DisplayName("El fondo de destino nace con los lotes y las fechas del de origen")
    void transferCarriesLotsAndDates() throws IOException {
        importFixture(ImportMode.ADD);

        List<FifoLot> b = fifoLotRepo.findByUserIdAndTickerOrderByPurchaseDateAscIdAsc(alice, NAME_B);
        assertEquals(2, b.size(), "un lote por cada fecha de adquisición heredada");

        // 120 títulos por 1500 € valen 12,50 cada uno: los 100 del primer lote son 1250 de los
        // 1500 traspasados, así que se llevan 62,5 de las 75 participaciones nuevas.
        assertEquals(LocalDate.of(2024, 1, 10), b.get(0).getPurchaseDate());
        assertEquals(0, new BigDecimal("62.5").compareTo(b.get(0).getInitialQty()));
        assertEquals(0, new BigDecimal("1000.00").compareTo(scale(b.get(0).getInitialCost())));

        assertEquals(LocalDate.of(2024, 2, 10), b.get(1).getPurchaseDate());
        assertEquals(0, new BigDecimal("12.5").compareTo(b.get(1).getInitialQty()));
        assertEquals(0, new BigDecimal("240.00").compareTo(scale(b.get(1).getInitialCost())));

        // Del fondo A queda lo que no se traspasó: 30 títulos del lote de febrero
        List<FifoLot> a = fifoLotRepo.findByUserIdAndTickerOrderByPurchaseDateAscIdAsc(alice, NAME_A);
        BigDecimal left = a.stream().map(FifoLot::getRemainingQty)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, new BigDecimal("30").compareTo(left));
    }

    @Test
    @DisplayName("Los fondos en divisa se guardan convertidos a euros")
    void convertsForeignCurrency() throws IOException {
        importFixture(ImportMode.ADD);

        for (Operation c : operations(NAME_C)) {
            assertEquals(0, new BigDecimal("100.00").compareTo(scale(c.getTotal())),
                    "125 USD a 1,25 USD/€ son 100 €");
        }
    }

    @Test
    @DisplayName("El ticker es el nombre del fondo y el ISIN se guarda aparte")
    void usesFundNameAsTicker() throws IOException {
        importFixture(ImportMode.ADD);

        Operation a = operations(NAME_A).get(0);
        assertEquals(NAME_A, a.getTicker());
        assertEquals(FUND_A, a.getAssetName(), "el ISIN es lo que sirve para cotizar");
    }

    @Test
    @DisplayName("Un fondo renombrado a mitad del fichero sigue siendo una sola posición")
    void renamedFundStaysOneTicker() throws IOException {
        importFixture(ImportMode.ADD);

        // Las dos compras del fondo C vienen con nombres distintos; si cada una se quedara con el
        // suyo habría dos posiciones y cada una haría su propio FIFO.
        assertEquals(2, operations(NAME_C).size());
        assertEquals(0, operations("FONDO C IDX P AC USD").size(),
                "el nombre antiguo no puede quedar como una posición aparte");
    }

    @Test
    @DisplayName("Un traspaso completo no genera ningún aviso")
    void balancedTransferIsSilent() throws IOException {
        assertEquals(List.of(), importFixture(ImportMode.ADD).warnings());
    }

    @Test
    @DisplayName("Avisa si al traspaso le falta una de las dos patas")
    void warnsAboutOrphanTransfer() {
        // Solo la entrada: es lo que pasa al importar un fichero que empieza más tarde que la
        // cartera, y significa que no hay coste que heredar.
        CsvImportResult result = csvService.importCsv(alice, table(
                row("2024-06-05", "SUSCR.POR TRASPASO I", FUND_B, "FONDO B", "75", "EUR", "1500.00")
        ), ImportMode.ADD);

        assertEquals(1, result.warnings().size(), result.warnings().toString());
        assertTrue(result.warnings().get(0).contains("no trae el fondo de origen"),
                result.warnings().get(0));
    }

    @Test
    @DisplayName("Avisa si lo que sale del traspaso no coincide con lo que entra")
    void warnsAboutUnbalancedTransfer() {
        // El extracto no ata las dos patas con ningún identificador: se agrupan por cercanía en
        // el tiempo, así que el importe es la única señal de que la agrupación ha fallado.
        CsvImportResult result = csvService.importCsv(alice, table(
                row("2024-01-10", "SUSCRIPCION", FUND_A, "FONDO A", "100", "EUR", "1000.00"),
                row("2024-06-03", "REEMB.POR TRASPASO I", FUND_A, "FONDO A", "60", "EUR", "750.00"),
                row("2024-06-05", "SUSCR.POR TRASPASO I", FUND_B, "FONDO B", "75", "EUR", "1500.00")
        ), ImportMode.ADD);

        assertEquals(1, result.warnings().size(), result.warnings().toString());
        assertTrue(result.warnings().get(0).contains("no cuadra"), result.warnings().get(0));
        assertTrue(result.warnings().get(0).contains("750.00"), result.warnings().get(0));
        assertTrue(result.warnings().get(0).contains("1500.00"), result.warnings().get(0));
    }

    @Test
    @DisplayName("Reimportar el mismo fichero no duplica nada")
    void reimportIsIdempotent() throws IOException {
        importFixture(ImportMode.ADD);
        CsvImportResult again = importFixture(ImportMode.ADD);

        assertEquals(0, again.operations());
        assertEquals(7, again.duplicates());
        assertEquals(7, operationRepo.findByUserId(alice).size());
    }

    @Test
    @DisplayName("Exportar al CSV propio y reimportar conserva los traspasos")
    void survivesOwnCsvRoundTrip() throws IOException {
        importFixture(ImportMode.ADD);
        String before = snapshot();

        byte[] exported = csvService.export(alice);
        CsvImportResult back = csvService.importCsv(alice, exported, ImportMode.REPLACE);

        assertEquals(List.of(), back.errors());
        assertEquals(OperationCsvService.FORMAT_OWN, back.format());
        // Sin la columna que empareja las patas, el fondo de destino perdería el coste heredado
        // y el FIFO saldría distinto: es justo lo que comprueba la huella.
        assertEquals(before, snapshot());
    }

    @Test
    @DisplayName("Recalcular la cartera entera deja el mismo resultado")
    void replayIsStable() throws IOException {
        importFixture(ImportMode.ADD);
        String before = snapshot();

        fifoService.recalculateAll(alice);

        assertEquals(before, snapshot());
    }

    // ─── Utilidades ──────────────────────────────────────────────────────────

    /** Extracto mínimo con las once columnas que sirve Inversis, para los casos de borde. */
    private static byte[] table(String... rows) {
        return ("<html><body><table border=\"1\">"
                + "<tr><th>Operación</th><th>Liquidación</th><th>Operación</th><th>Mercado</th>"
                + "<th>Operación</th><th>ISIN</th><th>Valor</th><th>Títulos/NOMINAL</th>"
                + "<th>Divisa</th><th>Precio Neto</th><th>Importe neto</th></tr>"
                + String.join("", rows)
                + "</table></body></html>").getBytes(StandardCharsets.ISO_8859_1);
    }

    private static String row(String date, String type, String isin, String name,
                              String qty, String currency, String amount) {
        return "<tr><td>" + date + "</td><td>" + date + "</td><td>1</td>"
                + "<td>FONDOS EXTRANJEROS</td><td>" + type + "</td><td>" + isin + "</td>"
                + "<td>" + name + "</td><td>" + qty + "</td><td>" + currency + "</td>"
                + "<td>1.0</td><td>" + amount + "</td></tr>";
    }

    private List<Operation> operations(String ticker) {
        return operationRepo.findByUserIdAndTickerOrderByDateAscIdAsc(alice, ticker);
    }

    private Operation single(String ticker, OperationType type) {
        List<Operation> found = operations(ticker).stream()
                .filter(o -> o.getType() == type).toList();
        assertEquals(1, found.size(), "se esperaba una sola operación " + type + " de " + ticker);
        return found.get(0);
    }

    private static BigDecimal scale(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    /** Huella del estado del FIFO: lotes vivos y ventas registradas. */
    private String snapshot() {
        String lots = fifoLotRepo.findByUserId(alice).stream()
                .map(l -> l.getTicker() + "|" + l.getPurchaseDate() + "|"
                        + l.getRemainingQty().stripTrailingZeros().toPlainString() + "|"
                        + scale(l.getRemainingCost()))
                .sorted().reduce("", (a, b) -> a + ";" + b);
        String sales = saleRecordRepo.findByUserId(alice).stream()
                .sorted(Comparator.comparing(SaleRecord::getSaleDate))
                .map(s -> s.getTicker() + "|" + s.getPurchaseDate() + "|" + scale(s.getGainLoss()))
                .reduce("", (a, b) -> a + ";" + b);
        return lots + "##" + sales;
    }
}
