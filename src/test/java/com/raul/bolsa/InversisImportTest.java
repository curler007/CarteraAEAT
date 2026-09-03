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
        // 3 compras + 1 salida + 1 entrada + 1 reembolso; las dos órdenes del 10/01 van juntas
        assertEquals(6, result.operations());
        assertEquals(1, result.ignoredCount(), "la comisión de gestión no mueve posiciones");

        Operation first = operations(FUND_A).get(0);
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

        assertEquals(OperationType.TRASPASO_OUT, single(FUND_A, OperationType.TRASPASO_OUT).getType());
        assertEquals(OperationType.TRASPASO_IN, single(FUND_B, OperationType.TRASPASO_IN).getType());

        // Las dos patas quedan emparejadas: es lo que permite que el coste viaje entre fondos
        String out = single(FUND_A, OperationType.TRASPASO_OUT).getTransferId();
        assertNotNull(out);
        assertEquals(out, single(FUND_B, OperationType.TRASPASO_IN).getTransferId());
    }

    @Test
    @DisplayName("Solo el reembolso llega a la declaración; el traspaso no genera ganancia")
    void onlyRedemptionIsTaxable() throws IOException {
        importFixture(ImportMode.ADD);

        List<SaleRecord> sales = saleRecordRepo.findByUserId(alice);
        assertEquals(1, sales.size(), "el traspaso no puede dejar rastro fiscal");

        SaleRecord sale = sales.get(0);
        assertEquals(FUND_B, sale.getTicker());
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

        // 1000 + 600 aportados al fondo A, más 100 € (125 USD) al fondo C
        BigDecimal contributed = operationRepo.findByUserId(alice).stream()
                .filter(o -> o.getType() == OperationType.BUY)
                .map(Operation::getTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, new BigDecimal("1700.00").compareTo(scale(contributed)));

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

        List<FifoLot> b = fifoLotRepo.findByUserIdAndTickerOrderByPurchaseDateAscIdAsc(alice, FUND_B);
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
        List<FifoLot> a = fifoLotRepo.findByUserIdAndTickerOrderByPurchaseDateAscIdAsc(alice, FUND_A);
        BigDecimal left = a.stream().map(FifoLot::getRemainingQty)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, new BigDecimal("30").compareTo(left));
    }

    @Test
    @DisplayName("Los fondos en divisa se guardan convertidos a euros")
    void convertsForeignCurrency() throws IOException {
        importFixture(ImportMode.ADD);

        Operation c = single(FUND_C, OperationType.BUY);
        assertEquals(0, new BigDecimal("100.00").compareTo(scale(c.getTotal())),
                "125 USD a 1,25 USD/€ son 100 €");
    }

    @Test
    @DisplayName("Reimportar el mismo fichero no duplica nada")
    void reimportIsIdempotent() throws IOException {
        importFixture(ImportMode.ADD);
        CsvImportResult again = importFixture(ImportMode.ADD);

        assertEquals(0, again.operations());
        assertEquals(6, again.duplicates());
        assertEquals(6, operationRepo.findByUserId(alice).size());
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
