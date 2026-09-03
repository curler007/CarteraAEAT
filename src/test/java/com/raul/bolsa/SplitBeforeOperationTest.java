package com.raul.bolsa;

import com.raul.bolsa.domain.AeatGroup;
import com.raul.bolsa.domain.FifoLot;
import com.raul.bolsa.domain.OperationType;
import com.raul.bolsa.repository.AppUserRepository;
import com.raul.bolsa.repository.FifoLotRepository;
import com.raul.bolsa.repository.OperationRepository;
import com.raul.bolsa.repository.SaleRecordRepository;
import com.raul.bolsa.repository.SplitRepository;
import com.raul.bolsa.service.OperationService;
import com.raul.bolsa.service.SplitService;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Una compra que se registra <em>despues</em> de un split, pero con fecha <em>anterior</em> a el,
 * tiene que recibir ese split.
 *
 * <p>Es el caso corriente al cargar el historico de un broker en una cartera que ya tiene splits
 * dados de alta: si el lote naciera sin aplicarselo, se quedaria con menos titulos de los que
 * corresponden y el error pasaria inadvertido hasta la siguiente venta.
 */
@SpringBootTest
class SplitBeforeOperationTest {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) throws IOException {
        Path db = Files.createTempDirectory("bolsa-split-order-").resolve("test.db");
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + db.toAbsolutePath());
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
        registry.add("app.security.username", () -> "test");
        registry.add("app.security.password", () -> "test");
    }

    @Autowired OperationService operationService;
    @Autowired SplitService splitService;
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
    @DisplayName("Una compra anterior a un split ya registrado recibe el split")
    void buyInsertedBeforeAnExistingSplitGetsIt() {
        // Se registra primero el split, como cuando lo acepta el detector de /splits
        registerSplit("2025-11-17", "10");

        // Y despues se carga una compra anterior, como al importar el historico del broker
        operationService.save(alice, buy("2025-10-06", "0.507717", "501"));

        FifoLot lot = onlyLot();
        assertEquals(0, new BigDecimal("5.07717").compareTo(lot.getRemainingQty()),
                "El split x10 debe aplicarse a una compra que es anterior a el. Obtenido: "
                        + lot.getRemainingQty());
        assertEquals(0, new BigDecimal("501").compareTo(lot.getRemainingCost()),
                "Un split reparte el mismo dinero en mas acciones: el coste no cambia");
    }

    @Test
    @DisplayName("Una compra posterior al split no lo recibe")
    void buyAfterTheSplitIsUntouched() {
        registerSplit("2025-11-17", "10");
        operationService.save(alice, buy("2025-12-01", "5", "501"));

        assertEquals(0, new BigDecimal("5").compareTo(onlyLot().getRemainingQty()),
                "Una compra posterior al split ya viene en acciones nuevas");
    }

    @Test
    @DisplayName("Una compra del mismo dia del split no lo recibe")
    void buyOnTheSplitDayIsUntouched() {
        registerSplit("2025-11-17", "10");
        operationService.save(alice, buy("2025-11-17", "5", "501"));

        // Mismo criterio que recalculateFifo, donde el split se aplica antes que las
        // operaciones de su misma fecha: el lote nace ya despues del split.
        assertEquals(0, new BigDecimal("5").compareTo(onlyLot().getRemainingQty()),
                "El split solo alcanza a lo comprado antes de su fecha");
    }

    @Test
    @DisplayName("Da igual el orden: registrar el split antes o despues da el mismo lote")
    void orderOfEntryDoesNotMatter() {
        registerSplit("2025-11-17", "10");
        operationService.save(alice, buy("2025-10-06", "0.507717", "501"));
        BigDecimal splitFirst = onlyLot().getRemainingQty();

        setUp();

        operationService.save(alice, buy("2025-10-06", "0.507717", "501"));
        registerSplit("2025-11-17", "10");
        BigDecimal operationFirst = onlyLot().getRemainingQty();

        assertEquals(0, splitFirst.compareTo(operationFirst),
                "El resultado no puede depender de en que orden se tecleen las cosas: "
                        + splitFirst + " vs " + operationFirst);
    }

    private FifoLot onlyLot() {
        List<FifoLot> lots = fifoLotRepo.findByUserId(alice);
        assertEquals(1, lots.size(), "Se esperaba un unico lote");
        return lots.get(0);
    }

    private void registerSplit(String date, String ratio) {
        SplitForm f = new SplitForm();
        f.setTicker("NETFLIX");
        f.setDate(LocalDate.parse(date));
        f.setRatio(new BigDecimal(ratio));
        splitService.save(alice, f);
    }

    private static OperationForm buy(String date, String qty, String total) {
        OperationForm f = new OperationForm();
        f.setType(OperationType.BUY);
        f.setTicker("NETFLIX");
        f.setAssetName("US64110L1061");
        f.setBroker("Trade Republic");
        f.setDate(LocalDate.parse(date));
        f.setQuantity(new BigDecimal(qty));
        f.setTotal(new BigDecimal(total));
        f.setCommission(BigDecimal.ZERO);
        f.setAeatGroup(AeatGroup.GROUP_2);
        return f;
    }
}
