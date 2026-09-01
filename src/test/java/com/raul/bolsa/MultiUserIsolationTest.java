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
import com.raul.bolsa.repository.SplitRepository;
import com.raul.bolsa.service.OperationService;
import com.raul.bolsa.service.SplitService;
import com.raul.bolsa.web.dto.OperationForm;
import com.raul.bolsa.web.dto.PortfolioItem;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Comprueba que las carteras de dos usuarios son completamente independientes.
 *
 * <p>Las fechas están entrelazadas a propósito: el lote de B cae cronológicamente
 * <em>entre</em> los dos lotes de A. Si el FIFO se saltara el filtro por usuario,
 * la venta de A consumiría el lote de B y el coste de adquisición saldría distinto,
 * así que una fuga no puede pasar desapercibida.
 */
@SpringBootTest
class MultiUserIsolationTest {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) throws IOException {
        Path db = Files.createTempDirectory("bolsa-isolation-").resolve("test.db");
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + db.toAbsolutePath());
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
        registry.add("app.security.username", () -> "test");
        registry.add("app.security.password", () -> "test");
    }

    @Autowired AppUserRepository userRepo;
    @Autowired OperationService operationService;
    @Autowired SplitService splitService;
    @Autowired OperationRepository operationRepo;
    @Autowired FifoLotRepository fifoLotRepo;
    @Autowired SaleRecordRepository saleRecordRepo;
    @Autowired SplitRepository splitRepo;

    private Long alice;
    private Long bob;

    /** Cada test parte de una BD limpia: comparten el mismo contexto de Spring. */
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

    @Test
    @DisplayName("La venta de un usuario nunca consume lotes de otro, aunque sean más antiguos")
    void fifoNeverCrossesUsers() {
        // Alice: dos compras de NVDA
        operationService.save(alice, buy("NVDA", "2020-01-01", "100", "1000"));
        operationService.save(alice, buy("NVDA", "2020-06-01", "100", "2000"));

        // Bob compra el mismo ticker en una fecha intermedia y mucho más caro.
        // Si hubiera fuga, la venta de Alice preferiría este lote por ser anterior al suyo de junio.
        operationService.save(bob, buy("NVDA", "2020-03-01", "100", "5000"));

        // Alice vende 150: debe consumir sus 100 de enero + 50 de junio
        operationService.save(alice, sell("NVDA", "2021-01-01", "150", "3000"));

        List<SaleRecord> aliceSales = saleRecordRepo.findByUserId(alice);
        assertEquals(2, aliceSales.size(), "Alice debería haber consumido exactamente 2 lotes propios");

        BigDecimal costBasis = sum(aliceSales, SaleRecord::getCostBasis);
        assertEquals(0, new BigDecimal("2000").compareTo(costBasis),
                "Coste de adquisición esperado 2000 (1000 de enero + la mitad de los 2000 de junio). "
                        + "Un 3500 significaría que se ha consumido el lote de Bob. Obtenido: " + costBasis);

        // Ninguna venta de Alice puede referenciar un lote cuyo propietario sea Bob
        assertTrue(aliceSales.stream().allMatch(sr -> alice.equals(sr.getConsumedLot().getUserId())),
                "Alice ha consumido un lote que no le pertenece");

        // El lote de Bob sigue intacto
        FifoLot bobLot = onlyLot(bob, "NVDA");
        assertEquals(0, new BigDecimal("100").compareTo(bobLot.getRemainingQty()),
                "El lote de Bob se ha consumido por la venta de Alice");
        assertEquals(0, new BigDecimal("5000").compareTo(bobLot.getRemainingCost()),
                "El coste del lote de Bob ha cambiado por la venta de Alice");

        // Y Bob no ve ninguna venta
        assertTrue(saleRecordRepo.findByUserId(bob).isEmpty(), "A Bob le han aparecido ventas ajenas");
    }

    @Test
    @DisplayName("Una venta pendiente de un usuario no bloquea las ventas de otro")
    void pendingSellDoesNotBlockOtherUsers() {
        // Alice vende TEF sin tener acciones: queda pendiente
        operationService.save(alice, sell("TEF", "2020-01-01", "100", "900"));
        Operation alicePending = onlyOperationOfType(alice, "TEF", OperationType.SELL);
        assertTrue(alicePending.isPending(), "La venta de Alice debería haber quedado pendiente");

        // Bob compra y vende TEF con normalidad, en fecha posterior a la venta pendiente de Alice
        operationService.save(bob, buy("TEF", "2019-01-01", "100", "500"));
        operationService.save(bob, sell("TEF", "2020-02-01", "100", "800"));

        Operation bobSell = onlyOperationOfType(bob, "TEF", OperationType.SELL);
        assertTrue(!bobSell.isPending(),
                "La venta pendiente de Alice ha bloqueado la de Bob: el filtro por usuario no se aplica");

        List<SaleRecord> bobSales = saleRecordRepo.findByUserId(bob);
        assertEquals(1, bobSales.size());
        assertEquals(0, new BigDecimal("300").compareTo(sum(bobSales, SaleRecord::getGainLoss)),
                "Ganancia esperada de Bob: 800 - 500 = 300");
    }

    @Test
    @DisplayName("Un split solo multiplica los lotes de quien lo registra")
    void splitAffectsOnlyItsOwner() {
        operationService.save(alice, buy("SPL", "2020-01-01", "10", "100"));
        operationService.save(bob, buy("SPL", "2020-01-01", "10", "100"));

        SplitForm form = new SplitForm();
        form.setTicker("SPL");
        form.setDate(LocalDate.parse("2020-06-01"));
        form.setRatio(new BigDecimal("10"));
        splitService.save(alice, form);

        assertEquals(0, new BigDecimal("100").compareTo(onlyLot(alice, "SPL").getRemainingQty()),
                "El split de Alice no se ha aplicado a su propio lote");
        assertEquals(0, new BigDecimal("10").compareTo(onlyLot(bob, "SPL").getRemainingQty()),
                "El split de Alice ha alterado el lote de Bob");
        assertTrue(splitRepo.findByUserId(bob).isEmpty(), "A Bob le ha aparecido un split ajeno");
    }

    @Test
    @DisplayName("Un usuario no puede editar ni borrar operaciones de otro")
    void cannotTouchAnotherUsersOperation() {
        operationService.save(alice, buy("REP", "2020-01-01", "10", "100"));
        Long aliceOpId = operationRepo.findByUserId(alice).get(0).getId();

        assertThrows(IllegalArgumentException.class,
                () -> operationService.update(bob, aliceOpId, buy("REP", "2020-01-01", "999", "999")),
                "Bob ha podido editar una operación de Alice");
        assertThrows(IllegalArgumentException.class,
                () -> operationService.delete(bob, aliceOpId),
                "Bob ha podido borrar una operación de Alice");

        // La operación de Alice sigue igual
        Operation op = operationRepo.findByIdAndUserId(aliceOpId, alice).orElseThrow();
        assertEquals(0, new BigDecimal("10").compareTo(op.getQuantity()));
        assertTrue(operationRepo.findByIdAndUserId(aliceOpId, bob).isEmpty(),
                "findByIdAndUserId ha devuelto a Bob una operación de Alice");
    }

    @Test
    @DisplayName("Cartera y ventas AEAT solo contienen filas propias, y ninguna fila queda sin dueño")
    void queriesReturnOnlyOwnRows() {
        operationService.save(alice, buy("AAA", "2020-01-01", "10", "100"));
        operationService.save(alice, sell("AAA", "2021-01-01", "5", "80"));
        operationService.save(bob, buy("BBB", "2020-01-01", "10", "200"));

        List<PortfolioItem> alicePortfolio = fifoLotRepo.findPortfolioSummary(alice);
        assertEquals(1, alicePortfolio.size());
        assertEquals("AAA", alicePortfolio.get(0).ticker());

        List<PortfolioItem> bobPortfolio = fifoLotRepo.findPortfolioSummary(bob);
        assertEquals(1, bobPortfolio.size());
        assertEquals("BBB", bobPortfolio.get(0).ticker());

        assertEquals(1, saleRecordRepo.findByUserIdAndTaxYearOrderBySaleDateAscTickerAsc(alice, 2021).size());
        assertTrue(saleRecordRepo.findByUserIdAndTaxYearOrderBySaleDateAscTickerAsc(bob, 2021).isEmpty(),
                "El informe AEAT de Bob incluye ventas de Alice");

        // Toda fila escrita por los servicios debe llevar propietario:
        // un user_id nulo la haría invisible en la app y rompería el FIFO.
        operationRepo.findAll().forEach(o -> assertNotNull(o.getUserId(), "Operación sin userId: " + o.getId()));
        fifoLotRepo.findAll().forEach(l -> assertNotNull(l.getUserId(), "Lote sin userId: " + l.getId()));
        saleRecordRepo.findAll().forEach(s -> assertNotNull(s.getUserId(), "Venta sin userId: " + s.getId()));
        splitRepo.findAll().forEach(s -> assertNotNull(s.getUserId(), "Split sin userId: " + s.getId()));
    }

    // ─── Utilidades ──────────────────────────────────────────────────────────

    private static BigDecimal sum(List<SaleRecord> records,
                                  java.util.function.Function<SaleRecord, BigDecimal> field) {
        return records.stream().map(field).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private FifoLot onlyLot(Long userId, String ticker) {
        List<FifoLot> lots = fifoLotRepo.findByUserIdAndTickerOrderByPurchaseDateAscIdAsc(userId, ticker);
        assertEquals(1, lots.size(), "Se esperaba exactamente un lote de " + ticker);
        return lots.get(0);
    }

    private Operation onlyOperationOfType(Long userId, String ticker, OperationType type) {
        List<Operation> ops = operationRepo.findByUserIdAndTickerOrderByDateAscIdAsc(userId, ticker)
                .stream().filter(o -> o.getType() == type).toList();
        assertEquals(1, ops.size(), "Se esperaba exactamente una operación " + type + " de " + ticker);
        return ops.get(0);
    }

    private static OperationForm buy(String ticker, String date, String qty, String total) {
        return form(OperationType.BUY, ticker, date, qty, total);
    }

    private static OperationForm sell(String ticker, String date, String qty, String total) {
        return form(OperationType.SELL, ticker, date, qty, total);
    }

    private static OperationForm form(OperationType type, String ticker,
                                      String date, String qty, String total) {
        OperationForm f = new OperationForm();
        f.setType(type);
        f.setTicker(ticker);
        f.setAssetName(ticker + " S.A.");
        f.setBroker("TestBroker");
        f.setDate(LocalDate.parse(date));
        f.setQuantity(new BigDecimal(qty));
        f.setTotal(new BigDecimal(total));
        f.setCommission(BigDecimal.ZERO);
        f.setAeatGroup(AeatGroup.GROUP_2);
        return f;
    }
}
