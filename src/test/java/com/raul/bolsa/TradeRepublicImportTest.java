package com.raul.bolsa;

import com.raul.bolsa.domain.AeatGroup;
import com.raul.bolsa.domain.Operation;
import com.raul.bolsa.domain.OperationType;
import com.raul.bolsa.repository.AppUserRepository;
import com.raul.bolsa.repository.FifoLotRepository;
import com.raul.bolsa.repository.OperationRepository;
import com.raul.bolsa.repository.SaleRecordRepository;
import com.raul.bolsa.repository.SplitRepository;
import com.raul.bolsa.service.OperationCsvService;
import com.raul.bolsa.web.dto.CsvImportResult;
import com.raul.bolsa.web.dto.ImportMode;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Importación de la "Exportación de transacción" de Trade Republic.
 *
 * <p>Las filas reproducen la forma real del fichero del broker (importes brutos, comisión
 * aparte, cantidades negativas en las ventas) pero con datos inventados.
 */
@SpringBootTest
class TradeRepublicImportTest {

    private static final String HEADER = "\"datetime\",\"date\",\"account_type\",\"category\","
            + "\"type\",\"asset_class\",\"name\",\"symbol\",\"shares\",\"price\",\"amount\","
            + "\"fee\",\"tax\",\"currency\",\"original_amount\",\"original_currency\",\"fx_rate\","
            + "\"description\",\"transaction_id\",\"counterparty_name\",\"counterparty_iban\","
            + "\"payment_reference\",\"mcc_code\"";

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) throws IOException {
        Path db = Files.createTempDirectory("bolsa-tr-").resolve("test.db");
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + db.toAbsolutePath());
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
        registry.add("app.security.username", () -> "test");
        registry.add("app.security.password", () -> "test");
    }

    @Autowired OperationCsvService csvService;
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
        alice = TestUsers.create(userRepo, "alice").getId();
    }

    @Test
    @DisplayName("Se importan solo las compras y ventas; el resto de movimientos se ignora")
    void importsOnlyTrades() {
        CsvImportResult result = csvService.importCsv(alice, file(
                cash("2025-07-26", "TRANSFER_INSTANT_INBOUND", "50.000000", "t1"),
                cash("2025-07-27", "DIVIDEND", "12.500000", "t2"),
                cash("2025-07-28", "INTEREST_PAYMENT", "0.850000", "t3"),
                buy("2025-07-28", "Apple", "US0378331005", "10.0000000000", "9.500000", "-95.00", "-1.00", "t4"),
                sell("2025-08-14", "Apple", "US0378331005", "-4.0000000000", "13.000000", "52.00", "-1.00", "t5")
        ), ImportMode.ADD);

        assertTrue(result.ok(), "No debería haber errores: " + result.errors());
        assertEquals(OperationCsvService.FORMAT_TRADE_REPUBLIC, result.format(),
                "El fichero debería reconocerse como de Trade Republic");
        assertEquals(2, result.operations(), "Solo la compra y la venta son operaciones");
        assertEquals(3, result.ignoredCount(),
                "Ingreso, dividendo e interés deberían quedar fuera. Ignorados: " + result.ignored());

        List<Operation> ops = sorted();
        assertEquals(2, ops.size());

        Operation compra = ops.get(0);
        assertEquals(OperationType.BUY, compra.getType());
        assertEquals(LocalDate.parse("2025-07-28"), compra.getDate());
        assertEquals("APPLE", compra.getTicker(), "El ticker sale del nombre del valor");
        assertEquals("US0378331005", compra.getAssetName());
        assertEquals("Trade Republic", compra.getBroker());
        assertEquals(0, new BigDecimal("10").compareTo(compra.getQuantity()));
        // En una compra la comisión suma al valor de adquisición: 95 + 1
        assertEquals(0, new BigDecimal("96.00").compareTo(compra.getTotal()),
                "El total de una compra incluye la comisión. Obtenido: " + compra.getTotal());
        assertEquals(0, new BigDecimal("1.00").compareTo(compra.getCommission()));
        assertEquals(AeatGroup.GROUP_3, compra.getAeatGroup(), "Un ISIN US es extraeuropeo");

        Operation venta = ops.get(1);
        assertEquals(OperationType.SELL, venta.getType());
        assertEquals(0, new BigDecimal("4").compareTo(venta.getQuantity()),
                "La cantidad vendida se toma en positivo");
        // En una venta la comisión resta del valor de transmisión: 52 - 1
        assertEquals(0, new BigDecimal("51.00").compareTo(venta.getTotal()),
                "El total de una venta va neto de comisión. Obtenido: " + venta.getTotal());
    }

    @Test
    @DisplayName("Reimportar el histórico completo no duplica lo ya cargado")
    void reimportIsIdempotent() {
        byte[] first = file(
                buy("2025-07-28", "Apple", "US0378331005", "10.0000000000", "9.500000", "-95.00", "-1.00", "t4")
        );
        assertEquals(1, csvService.importCsv(alice, first, ImportMode.ADD).operations());

        // El broker exporta siempre todo el histórico: la segunda descarga repite la compra
        byte[] second = file(
                buy("2025-07-28", "Apple", "US0378331005", "10.0000000000", "9.500000", "-95.00", "-1.00", "t4"),
                buy("2025-09-01", "Apple", "US0378331005", "5.0000000000", "10.000000", "-50.00", "-1.00", "t9")
        );
        CsvImportResult result = csvService.importCsv(alice, second, ImportMode.ADD);

        assertTrue(result.ok(), "No debería haber errores: " + result.errors());
        assertEquals(1, result.operations(), "Solo la operación nueva debería importarse");
        assertEquals(1, result.duplicates(), "La repetida debería contarse como duplicada");
        assertEquals(2, operationRepo.findByUserId(alice).size(),
                "La cartera debería tener dos operaciones, no tres");
    }

    @Test
    @DisplayName("Un valor que ya existe conserva su ticker y su grupo AEAT aunque el broker lo llame de otra forma")
    void reusesExistingTickerForKnownIsin() {
        // La cartera ya tiene MicroStrategy con nombre y grupo propios...
        csvService.importCsv(alice, ("﻿" + OperationCsvService.HEADER + "\n"
                + "01/06/2025;BUY;MICROSTRATEGY;US5949724083;ING;2;500;1;GROUP_2;\n")
                .getBytes(StandardCharsets.UTF_8), ImportMode.ADD);

        // ...y Trade Republic exporta el mismo ISIN con su nombre nuevo
        CsvImportResult result = csvService.importCsv(alice, file(
                buy("2025-09-01", "Strategy A", "US5949724083", "1.0000000000", "300.000000", "-300.00", "-1.00", "t7")
        ), ImportMode.ADD);

        assertTrue(result.ok(), "No debería haber errores: " + result.errors());
        assertTrue(operationRepo.findByUserId(alice).stream()
                        .allMatch(op -> op.getTicker().equals("MICROSTRATEGY")),
                "Un ticker distinto para el mismo ISIN partiría el FIFO en dos valores. Tickers: "
                        + operationRepo.findByUserId(alice).stream().map(Operation::getTicker).toList());
        assertTrue(operationRepo.findByUserId(alice).stream()
                        .allMatch(op -> op.getAeatGroup() == AeatGroup.GROUP_2),
                "El grupo AEAT que ya usaba el usuario debería respetarse");
    }

    @Test
    @DisplayName("Las cripto y las OPV sin importe se importan igualmente")
    void handlesCryptoAndMissingAmount() {
        CsvImportResult result = csvService.importCsv(alice, file(
                trade("2026-06-28", "TRADING", "BUY", "CRYPTO", "Bitcoin", "BTC",
                        "0.0050000000", "50000.000000", "-250.00", "", "t8"),
                // Adjudicación de OPV: sin amount, hay que valorarla con cantidad × precio
                trade("2026-06-12", "TRADING", "BUY", "STOCK", "SpaceX", "US84615Q1031",
                        "2.0000000000", "100.000000", "", "", "t10")
        ), ImportMode.ADD);

        assertTrue(result.ok(), "No debería haber errores: " + result.errors());
        assertEquals(2, result.operations());

        Operation bitcoin = sorted().stream()
                .filter(op -> op.getTicker().equals("BITCOIN")).findFirst().orElseThrow();
        assertEquals("XF000BTC0017", bitcoin.getAssetName(),
                "El símbolo BTC debe traducirse al identificador que la aplicación sabe cotizar");

        Operation spacex = sorted().stream()
                .filter(op -> op.getTicker().equals("SPACEX")).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("200.00").compareTo(spacex.getTotal()),
                "Sin importe, el total se reconstruye como 2 × 100. Obtenido: " + spacex.getTotal());
    }

    @Test
    @DisplayName("Las operaciones ya metidas a mano no se duplican al importar el fichero")
    void doesNotDuplicateManuallyEnteredOperations() {
        // El usuario ya había registrado a mano parte de su histórico del broker
        csvService.importCsv(alice, ("\ufeff" + OperationCsvService.HEADER + "\n"
                + "28/07/2025;BUY;APPLE;US0378331005;Trade Republic;10;96;1;GROUP_3;\n")
                .getBytes(StandardCharsets.UTF_8), ImportMode.ADD);

        // El fichero del broker trae esa misma compra y una posterior
        CsvImportResult result = csvService.importCsv(alice, file(
                buy("2025-07-28", "Apple", "US0378331005", "10.0000000000", "9.500000", "-95.00", "-1.00", "t4"),
                buy("2025-09-01", "Apple", "US0378331005", "5.0000000000", "10.000000", "-50.00", "-1.00", "t9")
        ), ImportMode.ADD);

        assertTrue(result.ok(), "No debería haber errores: " + result.errors());
        assertEquals(1, result.operations(), "Solo la compra que no tenía debería importarse");
        assertEquals(1, result.duplicates(), "La que ya estaba a mano debería reconocerse");
        assertEquals(2, operationRepo.findByUserId(alice).size(),
                "Sin esto, la primera importación duplicaría el histórico ya introducido");
    }

    @Test
    @DisplayName("Dos compras iguales el mismo día siguen siendo dos operaciones distintas")
    void identicalSameDayTradesAreNotCollapsed() {
        csvService.importCsv(alice, ("\ufeff" + OperationCsvService.HEADER + "\n"
                + "28/07/2025;BUY;APPLE;US0378331005;Trade Republic;10;96;1;GROUP_3;\n")
                .getBytes(StandardCharsets.UTF_8), ImportMode.ADD);

        // El broker exporta dos compras idénticas ese día: una ya la tenía, la otra no
        CsvImportResult result = csvService.importCsv(alice, file(
                buy("2025-07-28", "Apple", "US0378331005", "10.0000000000", "9.500000", "-95.00", "-1.00", "t4"),
                buy("2025-07-28", "Apple", "US0378331005", "10.0000000000", "9.500000", "-95.00", "-1.00", "t5")
        ), ImportMode.ADD);

        assertEquals(1, result.operations(),
                "Solo una de las dos coincide con la que ya estaba registrada; la otra es nueva");
        assertEquals(1, result.duplicates());
        assertEquals(2, operationRepo.findByUserId(alice).size(),
                "Deberían quedar las dos compras de ese día, no colapsarse en una");
    }

    @Test
    @DisplayName("Una compra anotada a mano que agrupa varias ejecuciones del broker no se duplica")
    void recognisesManualOperationAggregatingSeveralExecutions() {
        // Al teclearla se apuntó una sola compra de 10,421052 el día 28
        csvService.importCsv(alice, ("\ufeff" + OperationCsvService.HEADER + "\n"
                + "28/07/2025;BUY;ZEGONA;GB00BVGBY890;Trade Republic;10,421052;100;1;GROUP_2;\n")
                .getBytes(StandardCharsets.UTF_8), ImportMode.ADD);

        // El broker la exporta partida en las dos ejecuciones que casaron en mercado
        CsvImportResult result = csvService.importCsv(alice, file(
                buy("2025-07-28", "Zegona Communications PLC", "GB00BVGBY890",
                        "10.0000000000", "9.500000", "-95.00", "-1.00", "z1"),
                buy("2025-07-28", "Zegona Communications PLC", "GB00BVGBY890",
                        "0.4210520000", "9.500000", "-4.00", "", "z2")
        ), ImportMode.ADD);

        assertTrue(result.ok(), "No debería haber errores: " + result.errors());
        assertEquals(0, result.operations(),
                "Las dos ejecuciones suman la compra ya registrada, no hay nada nuevo");
        assertEquals(2, result.duplicates());
        assertEquals(1, operationRepo.findByUserId(alice).size(),
                "La cartera debería quedarse con la compra que ya tenía");
    }

    @Test
    @DisplayName("Si además de la agrupada hay una compra nueva ese día, la nueva sí entra")
    void importsNewTradeOnADayThatAlsoHasAnAggregatedOne() {
        csvService.importCsv(alice, ("\ufeff" + OperationCsvService.HEADER + "\n"
                + "28/07/2025;BUY;ZEGONA;GB00BVGBY890;Trade Republic;10,421052;100;1;GROUP_2;\n")
                .getBytes(StandardCharsets.UTF_8), ImportMode.ADD);

        CsvImportResult result = csvService.importCsv(alice, file(
                buy("2025-07-28", "Zegona Communications PLC", "GB00BVGBY890",
                        "10.0000000000", "9.500000", "-95.00", "-1.00", "z1"),
                buy("2025-07-28", "Zegona Communications PLC", "GB00BVGBY890",
                        "0.4210520000", "9.500000", "-4.00", "", "z2"),
                buy("2025-07-28", "Zegona Communications PLC", "GB00BVGBY890",
                        "3.0000000000", "9.500000", "-28.50", "-1.00", "z3")
        ), ImportMode.ADD);

        assertTrue(result.ok(), "No debería haber errores: " + result.errors());
        assertEquals(2, operationRepo.findByUserId(alice).size(),
                "Debería añadirse solo lo que no estaba. Operaciones: "
                        + operationRepo.findByUserId(alice).stream()
                        .map(op -> op.getQuantity().stripTrailingZeros().toPlainString()).toList());
    }

    @Test
    @DisplayName("Las entregas gratuitas entran con coste 0 y se avisa de que hay que valorarlas")
    void importsFreeReceiptsAtZeroCost() {
        CsvImportResult result = csvService.importCsv(alice, file(
                buy("2025-07-28", "Apple", "US0378331005", "10.0000000000", "9.500000", "-95.00", "-1.00", "t4"),
                // Traspaso desde otro broker: entran títulos y el fichero no dice a qué precio
                freeReceipt("2026-05-25", "DELIVERY", "STOCK", "Grifols (A)", "ES0171996087",
                        "138.0000000000", "", "g1")
        ), ImportMode.ADD);

        assertTrue(result.ok(), "No debería haber errores: " + result.errors());
        assertEquals(2, result.operations(), "La entrega también es una operación");

        Operation entrega = sorted().stream()
                .filter(op -> op.getTicker().equals("GRIFOLS (A)")).findFirst().orElseThrow();
        assertEquals(OperationType.BUY, entrega.getType(), "Entran títulos: es una entrada de lote");
        assertEquals(0, BigDecimal.ZERO.compareTo(entrega.getTotal()),
                "El fichero no dice cuánto costó, así que entra a cero");
        assertEquals(0, new BigDecimal("138").compareTo(entrega.getQuantity()));
        assertTrue(entrega.getNotes().contains("traspaso recibido sin coste"),
                "La nota debería decir de dónde sale. Obtenido: " + entrega.getNotes());

        assertEquals(1, result.pendingValuation().size(),
                "Solo la entrega necesita valoración, no la compra normal");
        assertTrue(result.pendingValuation().get(0).contains("25/05/2026")
                        && result.pendingValuation().get(0).contains("GRIFOLS (A)")
                        && result.pendingValuation().get(0).contains("138"),
                "El aviso debe identificar la operación: " + result.pendingValuation());
    }

    @Test
    @DisplayName("Reimportar no duplica una entrega gratuita ni vuelve a pedir su valoración")
    void freeReceiptIsNotDuplicatedOnReimport() {
        byte[] csv = file(freeReceipt("2026-06-28", "DELIVERY", "CRYPTO", "Bitcoin", "BTC",
                "0.0049960000", "51968.110000", "b1"));

        CsvImportResult first = csvService.importCsv(alice, csv, ImportMode.ADD);
        assertEquals(1, first.operations());
        assertEquals(1, first.pendingValuation().size());

        // La coletilla de la nota no puede estropear la lectura del transaction_id
        CsvImportResult second = csvService.importCsv(alice, csv, ImportMode.ADD);
        assertEquals(0, second.operations(), "Ya estaba importada");
        assertEquals(1, second.duplicates());
        assertTrue(second.pendingValuation().isEmpty(),
                "No se debe volver a pedir valorar algo que ya estaba");
        assertEquals(1, operationRepo.findByUserId(alice).size());
    }

    @Test
    @DisplayName("Un fichero sin ninguna compra ni venta no importa nada y lo explica")
    void rejectsFileWithoutTrades() {
        CsvImportResult result = csvService.importCsv(alice, file(
                cash("2025-07-26", "TRANSFER_INSTANT_INBOUND", "50.000000", "t1")
        ), ImportMode.ADD);

        assertFalse(result.ok(), "No hay nada que importar, debería informar del motivo");
        assertTrue(result.errors().get(0).contains("compra"),
                "El mensaje debería explicar que no hay compras ni ventas: " + result.errors());
        assertTrue(operationRepo.findByUserId(alice).isEmpty());
    }

    @Test
    @DisplayName("El formato propio se sigue detectando y sigue importando splits")
    void ownFormatStillWorks() {
        CsvImportResult result = csvService.importCsv(alice, ("﻿" + OperationCsvService.HEADER + "\n"
                + "01/06/2025;BUY;NVIDIA;US67066G1040;ING;10;1000;1;GROUP_3;\n"
                + "10/06/2025;SPLIT;NVIDIA;;;10;;;;\n")
                .getBytes(StandardCharsets.UTF_8), ImportMode.ADD);

        assertTrue(result.ok(), "No debería haber errores: " + result.errors());
        assertEquals(OperationCsvService.FORMAT_OWN, result.format());
        assertEquals(1, result.operations());
        assertEquals(1, result.splits(), "El formato propio sí trae splits");
        assertEquals(0, result.ignoredCount());
        assertNull(operationRepo.findByUserId(alice).get(0).getNotes(),
                "El formato propio no debe marcar las notas con el id de Trade Republic");
    }

    private List<Operation> sorted() {
        return operationRepo.findByUserId(alice).stream()
                .sorted(Comparator.comparing(Operation::getDate).thenComparing(Operation::getId))
                .toList();
    }

    // ─── Construcción de filas con la forma real del fichero ─────────────────

    private static byte[] file(String... rows) {
        return (HEADER + "\n" + String.join("\n", rows) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static String buy(String date, String name, String isin, String shares,
                              String price, String amount, String fee, String txId) {
        return trade(date, "TRADING", "BUY", "STOCK", name, isin, shares, price, amount, fee, txId);
    }

    private static String sell(String date, String name, String isin, String shares,
                               String price, String amount, String fee, String txId) {
        return trade(date, "TRADING", "SELL", "STOCK", name, isin, shares, price, amount, fee, txId);
    }

    /** Entrega de títulos sin pago: hay cantidad, pero no importe. */
    private static String freeReceipt(String date, String category, String assetClass, String name,
                                      String symbol, String shares, String price, String txId) {
        return trade(date, category, "FREE_RECEIPT", assetClass, name, symbol,
                shares, price, "", "", txId);
    }

    /** Movimiento de efectivo: sin valor, sin cantidad y sin precio. */
    private static String cash(String date, String type, String amount, String txId) {
        return trade(date, "CASH", type, "", "", "", "", "", amount, "", txId);
    }

    private static String trade(String date, String category, String type, String assetClass,
                                String name, String symbol, String shares, String price,
                                String amount, String fee, String txId) {
        return q(date + "T00:00:00Z", date, "DEFAULT", category, type, assetClass, name, symbol,
                shares, price, amount, fee, "", "EUR", "", "", "", "descripción", txId,
                "", "", "", "");
    }

    private static String q(String... fields) {
        return "\"" + String.join("\",\"", fields) + "\"";
    }
}
