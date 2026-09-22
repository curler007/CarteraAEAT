package com.raul.bolsa;

import com.raul.bolsa.domain.AeatGroup;
import com.raul.bolsa.domain.Operation;
import com.raul.bolsa.domain.OperationType;
import com.raul.bolsa.domain.SaleRecord;
import com.raul.bolsa.repository.AppUserRepository;
import com.raul.bolsa.repository.FifoLotRepository;
import com.raul.bolsa.repository.OperationRepository;
import com.raul.bolsa.repository.SaleRecordRepository;
import com.raul.bolsa.repository.SplitRepository;
import com.raul.bolsa.service.EcbFxRateService;
import com.raul.bolsa.service.OperationCsvService;
import com.raul.bolsa.service.OperationService;
import com.raul.bolsa.web.dto.CsvImportResult;
import com.raul.bolsa.web.dto.ImportMode;
import com.raul.bolsa.web.dto.OperationForm;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Importación del Activity Statement de Interactive Brokers.
 *
 * <p>Los ficheros de ejemplo reproducen la forma real del extracto —una sección y un tipo de fila
 * por línea, cabeceras que se repiten al cambiar de categoría, números a la americana con coma de
 * millares— con valores reales pero cifras inventadas:
 *
 * <pre>
 *   ibkr-ejemplo.csv            compras y ventas de acciones en USD y en EUR, más una opción
 *                               y un cambio de divisa, que no deben entrar
 *   ibkr-desglose-ordenes.csv   cada orden con sus ejecuciones debajo y el ClosedLot del FIFO
 *                               del propio broker: solo la orden es un movimiento
 *   ibkr-sin-instrumentos.csv   sin la sección que traduce el símbolo a ISIN
 *   ibkr-divisa-sin-tipo.csv    en una divisa para la que el BCE no da tipo
 * </pre>
 *
 * <p>El tipo de cambio se fija a 1,10 USD/€ en vez de consultar al BCE: lo que se comprueba es la
 * conversión, no la red. La libra se deja sin tipo a propósito, para poder ver qué pasa cuando
 * falta.
 */
@SpringBootTest
class InteractiveBrokersImportTest {

    private static final Path EJEMPLO = Path.of("src/test/resources/ibkr-ejemplo.csv");
    private static final Path DESGLOSE = Path.of("src/test/resources/ibkr-desglose-ordenes.csv");
    private static final Path SIN_ISIN = Path.of("src/test/resources/ibkr-sin-instrumentos.csv");
    private static final Path SIN_TIPO = Path.of("src/test/resources/ibkr-divisa-sin-tipo.csv");

    private static final BigDecimal USD_PER_EUR = new BigDecimal("1.10");

    private static final String APPLE = "US0378331005";
    private static final String MICROSOFT = "US5949181045";
    private static final String SANTANDER = "ES0113900J37";
    private static final String INDITEX = "ES0148396007";
    private static final String REPSOL = "ES0173516115";
    private static final String TELEFONICA = "ES0178430E18";
    private static final String IBERDROLA = "ES0144580Y14";

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) throws IOException {
        Path db = Files.createTempDirectory("bolsa-ibkr-").resolve("test.db");
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + db.toAbsolutePath());
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
        registry.add("app.security.username", () -> "test");
        registry.add("app.security.password", () -> "test");
    }

    /** El BCE no se consulta en los tests: 1,10 dólares por euro, y la libra sin tipo. */
    @MockBean EcbFxRateService fxRates;

    @Autowired OperationCsvService csvService;
    @Autowired OperationService operationService;
    @Autowired AppUserRepository userRepo;
    @Autowired OperationRepository operationRepo;
    @Autowired FifoLotRepository fifoLotRepo;
    @Autowired SaleRecordRepository saleRecordRepo;
    @Autowired SplitRepository splitRepo;

    private Long alice;

    @BeforeEach
    void setUp() {
        saleRecordRepo.deleteAll();
        fifoLotRepo.deleteAll();
        operationRepo.deleteAll();
        splitRepo.deleteAll();
        userRepo.deleteAll();
        alice = TestUsers.create(userRepo, "alice-ibkr").getId();

        Mockito.when(fxRates.toEur(Mockito.any(), Mockito.anyString(), Mockito.any()))
                .thenAnswer(call -> {
                    BigDecimal amount = call.getArgument(0);
                    String currency = call.getArgument(1);
                    if ("EUR".equals(currency)) return Optional.of(amount);
                    if ("USD".equals(currency)) {
                        return Optional.of(amount.divide(USD_PER_EUR, 6, RoundingMode.HALF_UP));
                    }
                    return Optional.empty();
                });
    }

    // ─── Caso completo ───────────────────────────────────────────────────────

    @Test
    @DisplayName("Importa las acciones y deja fuera opciones y cambios de divisa")
    void importsStocksOnly() throws IOException {
        CsvImportResult result = importFile(EJEMPLO, ImportMode.ADD);

        assertEquals(List.of(), result.errors());
        assertEquals(OperationCsvService.FORMAT_IBKR, result.format());
        assertEquals(4, result.operations(), "Dos de Apple, una de Microsoft y una de Santander");
        assertEquals(java.util.Map.of("Equity and Index Options", 1, "Forex", 1), result.ignored(),
                "La opción y el cambio de euros a dólares se cuentan, pero no entran");

        List<Operation> ops = sorted();
        assertEquals(4, ops.size());
        assertEquals(List.of("AAPL", "MSFT", "SAN", "AAPL"),
                ops.stream().map(Operation::getTicker).toList());
        assertTrue(ops.stream().allMatch(op -> "Interactive Brokers".equals(op.getBroker())));
    }

    @Test
    @DisplayName("La compra suma la comisión al coste y la venta la descuenta, ya en euros")
    void convertsAmountsToEuros() throws IOException {
        importFile(EJEMPLO, ImportMode.ADD);
        List<Operation> ops = sorted();

        Operation compra = ops.get(0);
        assertEquals(OperationType.BUY, compra.getType());
        assertEquals(LocalDate.parse("2025-02-11"), compra.getDate());
        assertEquals(APPLE, compra.getAssetName());
        assertEquals(AeatGroup.GROUP_3, compra.getAeatGroup(), "Valor estadounidense");
        assertEquals(0, new BigDecimal("20").compareTo(compra.getQuantity()));
        // 3.706,00 $ de importe + 1,0523 $ de comisión, a 1,10 $/€
        assertEquals(0, eur("3707.0523").compareTo(compra.getTotal()),
                "El total debería ser el importe más la comisión, en euros");
        assertEquals(0, eur("1.0523").compareTo(compra.getCommission()));

        Operation venta = ops.get(3);
        assertEquals(OperationType.SELL, venta.getType());
        assertEquals(LocalDate.parse("2025-09-18"), venta.getDate());
        assertEquals(0, new BigDecimal("8").compareTo(venta.getQuantity()),
                "La cantidad negativa del extracto es la señal de que es una venta");
        // 1.774,00 $ de importe - 1,0411 $ de comisión
        assertEquals(0, eur("1772.9589").compareTo(venta.getTotal()),
                "En una venta la comisión resta del valor de transmisión");

        Operation santander = ops.get(2);
        assertEquals(AeatGroup.GROUP_1, santander.getAeatGroup(), "Valor español");
        assertEquals(0, new BigDecimal("1444.80").compareTo(santander.getTotal()),
                "Lo que ya viene en euros no se convierte");
    }

    @Test
    @DisplayName("Reimportar el mismo extracto no duplica nada")
    void reimportIsHarmless() throws IOException {
        importFile(EJEMPLO, ImportMode.ADD);
        CsvImportResult again = importFile(EJEMPLO, ImportMode.ADD);

        assertEquals(List.of(), again.errors());
        assertEquals(0, again.operations(), "No hay nada nuevo que dar de alta");
        assertEquals(4, again.duplicates(), "Las cuatro ya estaban");
        assertEquals(4, operationRepo.findByUserId(alice).size());
    }

    // ─── Desglose de órdenes ─────────────────────────────────────────────────

    @Test
    @DisplayName("Con el desglose activado se cuenta la orden, no sus ejecuciones")
    void prefersOrdersOverExecutions() throws IOException {
        CsvImportResult result = importFile(DESGLOSE, ImportMode.ADD);

        assertEquals(List.of(), result.errors());
        assertEquals(3, result.operations(),
                "Compra y venta de Inditex más la compra de Repsol: las ejecuciones y el "
                        + "ClosedLot son el desglose de esas mismas órdenes");

        List<Operation> ops = sorted();
        assertEquals(0, new BigDecimal("100").compareTo(ops.get(0).getQuantity()),
                "La orden entera, no los 40 de la primera ejecución");
        assertEquals(INDITEX, ops.get(0).getAssetName());
        assertEquals(REPSOL, ops.get(1).getAssetName());
        assertEquals(OperationType.SELL, ops.get(2).getType());

        // Y el FIFO cuadra con la ganancia que el propio extracto declara.
        List<SaleRecord> sales = saleRecordRepo.findByUserId(alice);
        assertEquals(1, sales.size());
        assertEquals(0, new BigDecimal("4501.50").compareTo(sales.get(0).getCostBasis()));
        assertEquals(0, new BigDecimal("5198.50").compareTo(sales.get(0).getProceeds()));
        assertEquals(0, new BigDecimal("697.00").compareTo(sales.get(0).getGainLoss()),
                "Los 697 € de Realized P/L que dice el extracto");
    }

    // ─── Resolución del ISIN ─────────────────────────────────────────────────

    @Test
    @DisplayName("Sin ISIN y sin nada con qué deducirlo, no se importa nada y se dice por qué")
    void refusesWhenIsinIsUnknown() throws IOException {
        CsvImportResult result = importFile(SIN_ISIN, ImportMode.ADD);

        assertFalse(result.ok());
        assertEquals(2, result.errors().size(),
                "Un aviso por símbolo, no uno por línea: " + result.errors());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("TEF")));
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("IBE")));
        assertTrue(operationRepo.findByUserId(alice).isEmpty(),
                "Ni siquiera a medias: o entra el extracto entero o no entra nada");
    }

    @Test
    @DisplayName("Si el valor ya está en tu cartera, el ISIN sale de ahí")
    void resolvesIsinFromPortfolio() throws IOException {
        manual("2024-02-01", "TEF", TELEFONICA, "100", "380");
        manual("2024-02-01", "IBE", IBERDROLA, "50", "550");

        CsvImportResult result = importFile(SIN_ISIN, ImportMode.ADD);

        assertEquals(List.of(), result.errors());
        assertEquals(2, result.operations());
        List<Operation> importadas = operationRepo.findByUserId(alice).stream()
                .filter(op -> "Interactive Brokers".equals(op.getBroker()))
                .sorted(Comparator.comparing(Operation::getDate))
                .toList();
        assertEquals(TELEFONICA, importadas.get(0).getAssetName());
        assertEquals(IBERDROLA, importadas.get(1).getAssetName());
    }

    @Test
    @DisplayName("Un valor que ya tenías conserva su ticker, aunque IB lo llame de otra forma")
    void keepsTheTickerYouAlreadyUse() throws IOException {
        // La misma Apple comprada en otro broker y anotada como APPLE, no como AAPL.
        manual("2024-01-10", "APPLE", APPLE, "5", "700");

        importFile(EJEMPLO, ImportMode.ADD);

        List<Operation> apple = operationRepo.findByUserId(alice).stream()
                .filter(op -> APPLE.equals(op.getAssetName()))
                .toList();
        assertEquals(3, apple.size());
        assertTrue(apple.stream().allMatch(op -> "APPLE".equals(op.getTicker())),
                "Dos tickers para el mismo ISIN partirían el FIFO en dos posiciones");
    }

    // ─── Solapamiento con lo apuntado a mano ─────────────────────────────────

    @Test
    @DisplayName("Lo que ya habías apuntado a mano no vuelve a entrar")
    void doesNotReimportWhatYouTypedYourself() throws IOException {
        // La misma compra de Microsoft del extracto, tecleada en su día con la comisión redondeada.
        manual("2025-03-04", "MSFT", MICROSOFT, "5", "1810.45");

        CsvImportResult result = importFile(EJEMPLO, ImportMode.ADD);

        assertEquals(List.of(), result.errors());
        assertEquals(3, result.operations(), "Microsoft ya estaba");
        assertEquals(1, result.duplicates());
        assertEquals(1, operationRepo.findByUserId(alice).stream()
                .filter(op -> MICROSOFT.equals(op.getAssetName())).count());
    }

    // ─── Divisa sin tipo ─────────────────────────────────────────────────────

    @Test
    @DisplayName("Si el BCE no tiene tipo para esa divisa, se rechaza en vez de inventarlo")
    void refusesWhenThereIsNoExchangeRate() throws IOException {
        CsvImportResult result = importFile(SIN_TIPO, ImportMode.ADD);

        assertFalse(result.ok());
        assertEquals(1, result.errors().size(), "Errores: " + result.errors());
        assertTrue(result.errors().get(0).contains("GBP"), result.errors().get(0));
        assertTrue(operationRepo.findByUserId(alice).isEmpty());
    }

    // ─── Identidad de las operaciones ────────────────────────────────────────

    @Test
    @DisplayName("Cada operación queda marcada con el valor, el momento y el sentido")
    void marksEveryOperationWithItsOrigin() throws IOException {
        importFile(EJEMPLO, ImportMode.ADD);

        Operation compra = sorted().get(0);
        assertNotNull(compra.getNotes());
        assertTrue(compra.getNotes().startsWith(
                        "IBKR:" + APPLE + ":2025-02-11T15:42:07:BUY:20"),
                "Notas inesperadas: " + compra.getNotes());
        assertTrue(compra.getNotes().endsWith("APPLE INC"),
                "El nombre del valor ayuda a reconocerla en el listado: " + compra.getNotes());
    }

    @Test
    @DisplayName("Dos ejecuciones idénticas en el mismo segundo son dos operaciones, no una")
    void tellsApartTwoIdenticalExecutions() {
        // Mismo valor, mismo segundo, misma cantidad y mismo importe: lo único que las distingue
        // es que el extracto trae dos. Si la identidad no lo recogiera, la segunda se daría por
        // ya importada y la cartera se quedaría con la mitad de los títulos.
        byte[] file = ("Statement,Header,Field Name,Field Value\n"
                + "Statement,Data,Title,Activity Statement\n"
                + "Trades,Header,DataDiscriminator,Asset Category,Currency,Symbol,Date/Time,"
                + "Quantity,T. Price,Proceeds,Comm/Fee,Code\n"
                + "Trades,Data,Order,Stocks,EUR,ITX,\"2025-06-12, 09:30:00\",50,45.0000,"
                + "\"-2,250.00\",-1.0000,O\n"
                + "Trades,Data,Order,Stocks,EUR,ITX,\"2025-06-12, 09:30:00\",50,45.0000,"
                + "\"-2,250.00\",-1.0000,O\n"
                + "Financial Instrument Information,Header,Asset Category,Symbol,Description,"
                + "Conid,Security ID,Listing Exch,Multiplier,Type,Code\n"
                + "Financial Instrument Information,Data,Stocks,ITX,INDUSTRIA DE DISENO TEXTIL SA,"
                + "1234567," + INDITEX + ",BM,1,COMMON,\n").getBytes(StandardCharsets.UTF_8);

        CsvImportResult first = csvService.importCsv(alice, file, ImportMode.ADD);
        assertEquals(List.of(), first.errors());
        assertEquals(2, first.operations(), "Son dos compras de 50, no una de 50 repetida");
        assertEquals(0, new BigDecimal("100").compareTo(
                        operationRepo.findByUserId(alice).stream()
                                .map(Operation::getQuantity)
                                .reduce(BigDecimal.ZERO, BigDecimal::add)),
                "Los 100 títulos tienen que estar enteros");

        // Y al reimportar el mismo extracto siguen siendo las mismas dos, no dos más.
        CsvImportResult again = csvService.importCsv(alice, file, ImportMode.ADD);
        assertEquals(0, again.operations());
        assertEquals(2, again.duplicates());
        assertEquals(2, operationRepo.findByUserId(alice).size());
    }

    @Test
    @DisplayName("En modo reemplazar, el extracto se convierte en toda la cartera")
    void replaceModeRebuildsThePortfolio() throws IOException {
        manual("2024-01-10", "APPLE", APPLE, "5", "700");
        manual("2024-03-02", "TEF", TELEFONICA, "100", "380");

        CsvImportResult result = importFile(EJEMPLO, ImportMode.REPLACE);

        assertEquals(List.of(), result.errors());
        assertEquals(4, result.operations());
        assertEquals(0, result.duplicates(),
                "Al vaciar la cartera no queda nada con lo que chocar");

        List<Operation> ops = operationRepo.findByUserId(alice);
        assertEquals(4, ops.size(), "Las dos anteriores se han ido");
        assertTrue(ops.stream().allMatch(op -> "Interactive Brokers".equals(op.getBroker())));
        assertTrue(ops.stream().anyMatch(op -> "AAPL".equals(op.getTicker())),
                "Sin cartera previa de la que heredarlo, el ticker es el símbolo de IB");
    }

    @Test
    @DisplayName("Un extracto con los números a la europea se rechaza, no se lee al revés")
    void refusesEuropeanNumbers() {
        // 2.250,00 con el punto de millares: quitarle la coma daría 2.25000 y entrarían 2,25 €
        // en vez de 2.250 €. Es el error que no se ve, así que tiene que doler aquí.
        byte[] file = ("Statement,Header,Field Name,Field Value\n"
                + "Statement,Data,Title,Activity Statement\n"
                + "Trades,Header,DataDiscriminator,Asset Category,Currency,Symbol,Date/Time,"
                + "Quantity,T. Price,Proceeds,Comm/Fee,Code\n"
                + "Trades,Data,Order,Stocks,EUR,ITX,\"2025-06-12, 09:30:00\",50,45.0000,"
                + "\"-2.250,00\",-1.0000,O\n"
                + "Financial Instrument Information,Header,Asset Category,Symbol,Description,"
                + "Conid,Security ID,Listing Exch,Multiplier,Type,Code\n"
                + "Financial Instrument Information,Data,Stocks,ITX,INDUSTRIA DE DISENO TEXTIL SA,"
                + "1234567," + INDITEX + ",BM,1,COMMON,\n").getBytes(StandardCharsets.UTF_8);

        CsvImportResult result = csvService.importCsv(alice, file, ImportMode.ADD);

        assertFalse(result.ok());
        assertEquals(1, result.errors().size(), "Errores: " + result.errors());
        assertTrue(result.errors().get(0).contains("millares"), result.errors().get(0));
        assertTrue(operationRepo.findByUserId(alice).isEmpty());
    }

    // ─── Apoyo ───────────────────────────────────────────────────────────────

    private CsvImportResult importFile(Path fixture, ImportMode mode) throws IOException {
        return csvService.importCsv(alice, Files.readAllBytes(fixture), mode);
    }

    /** Importe en euros al cambio del test, con la escala con la que lo guarda el conversor. */
    private static BigDecimal eur(String usd) {
        return new BigDecimal(usd).divide(USD_PER_EUR, 6, RoundingMode.HALF_UP);
    }

    /** Una compra tecleada por el usuario, de las que no llevan marca de importación. */
    private void manual(String date, String ticker, String isin, String qty, String total) {
        OperationForm f = new OperationForm();
        f.setDate(LocalDate.parse(date));
        f.setType(OperationType.BUY);
        f.setTicker(ticker);
        f.setAssetName(isin);
        f.setBroker("Otro broker");
        f.setQuantity(new BigDecimal(qty));
        f.setTotal(new BigDecimal(total));
        f.setCommission(BigDecimal.ZERO);
        f.setAeatGroup(AeatGroup.forIsin(isin));
        operationService.save(alice, f);
    }

    private List<Operation> sorted() {
        return operationRepo.findByUserId(alice).stream()
                .sorted(Comparator.comparing(Operation::getDate))
                .toList();
    }
}
