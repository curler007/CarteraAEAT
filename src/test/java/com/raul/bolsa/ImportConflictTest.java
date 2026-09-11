package com.raul.bolsa;

import com.raul.bolsa.domain.AeatGroup;
import com.raul.bolsa.domain.AppUser;
import com.raul.bolsa.domain.Operation;
import com.raul.bolsa.domain.OperationType;
import com.raul.bolsa.repository.AppUserRepository;
import com.raul.bolsa.repository.OperationRepository;
import com.raul.bolsa.service.OperationCsvService;
import com.raul.bolsa.service.OperationService;
import com.raul.bolsa.web.dto.CsvImportResult;
import com.raul.bolsa.web.dto.ImportConflict;
import com.raul.bolsa.web.dto.ImportMode;
import com.raul.bolsa.web.dto.OperationForm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import com.raul.bolsa.security.AppUserPrincipal;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DefaultCsrfToken;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Reimportar una exportación propia ya no duplica nada, porque cada operación viaja con su uid.
 *
 * <p>Eso deja un caso que antes no existía: el fichero trae una operación que ya está, pero con
 * algún dato distinto. Puede ser una corrección hecha en la hoja de cálculo o un fichero viejo
 * que desharía cambios recientes, y desde dentro del importador las dos se ven exactamente igual.
 * Así que no se elige por el usuario: se para todo y se le enseña qué cambiaría.
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
class ImportConflictTest {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) throws IOException {
        Path db = Files.createTempDirectory("bolsa-conflict-").resolve("test.db");
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + db.toAbsolutePath());
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
        registry.add("app.security.username", () -> "conflicto");
        registry.add("app.security.password", () -> "conflicto");
    }

    @Autowired AppUserRepository userRepo;
    @Autowired OperationRepository operationRepo;
    @Autowired OperationService operationService;
    @Autowired OperationCsvService csvService;
    @Autowired MockMvc mvc;

    private Long uid;

    @BeforeEach
    void setUp(TestInfo info) {
        AppUser user = TestUsers.create(userRepo,
                "conflicto-" + info.getTestMethod().orElseThrow().getName());
        uid = user.getId();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new AppUserPrincipal(user), null, List.of()));
        operationService.save(uid, form("2025-03-10", "APPLE", "US0378331005", "10", "1500"));
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /**
     * El recorrido entero por la web, que es donde vive el riesgo: la página de decisión es
     * marcado de plantilla, y si una expresión se rompe el compilador no dice nada.
     */
    @Test
    @DisplayName("La web enseña la tabla de diferencias y aplica lo que se marque")
    void elRecorridoPorLaWeb() throws Exception {
        // Con los filtros de seguridad apagados nadie pone el token, y la barra de navegación
        // que traen todas las páginas lo necesita para el formulario de salir.
        CsrfToken csrf = new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "token-de-prueba");

        MvcResult subida = mvc.perform(multipart("/operations/import")
                        .file(new MockMultipartFile("file", "cartera.csv", "text/csv",
                                cambiandoElTotal("1600")))
                        .param("mode", "ADD")
                        .requestAttr(CsrfToken.class.getName(), csrf)
                        .requestAttr("_csrf", csrf))
                .andExpect(status().isOk())
                .andExpect(view().name("operations/import-conflicts"))
                .andReturn();

        String html = subida.getResponse().getContentAsString();
        assertTrue(html.contains("APPLE"), "la tabla debe decir de qué operación se trata");
        assertTrue(html.contains("Total"), "y qué campo cambia");
        assertTrue(html.contains("1500") && html.contains("1600"),
                "enseñando el valor de antes y el del fichero: " + html);
        assertEquals(0, total().compareTo(new BigDecimal("1500")),
                "mientras se pregunta no se ha escrito nada");

        String identidad = operationRepo.findByUserId(uid).get(0).getUid();
        HttpSession sesion = subida.getRequest().getSession(false);
        assertTrue(sesion != null, "el fichero tiene que esperar en la sesión a la respuesta");

        mvc.perform(post("/operations/import/resolver")
                        .session((org.springframework.mock.web.MockHttpSession) sesion)
                        .param("update", identidad))
                .andExpect(status().is3xxRedirection());

        assertEquals(0, total().compareTo(new BigDecimal("1600")),
                "lo marcado para actualizar sí se aplica");
    }

    @Test
    @DisplayName("Al crearla, la operación recibe una identidad estable que viaja en el CSV")
    void laOperacionNaceConIdentidad() {
        Operation op = operationRepo.findByUserId(uid).get(0);

        assertTrue(op.getUid() != null && !op.getUid().isBlank(), "toda operación debe nacer con uid");
        assertTrue(csv().contains(op.getUid()), "y el uid debe salir en la exportación");
        assertTrue(csv().startsWith("﻿" + OperationCsvService.HEADER)
                        || csv().contains(OperationCsvService.HEADER),
                "la cabecera debe anunciar la columna: " + OperationCsvService.HEADER);
    }

    @Test
    @DisplayName("Editar una operación no le cambia la identidad")
    void editarNoCambiaLaIdentidad() {
        Operation antes = operationRepo.findByUserId(uid).get(0);
        String identidad = antes.getUid();

        OperationForm f = form("2025-03-10", "APPLE", "US0378331005", "10", "1600");
        f.setUid(identidad);
        operationService.update(uid, antes.getId(), f);

        assertEquals(identidad, operationRepo.findByUserId(uid).get(0).getUid(),
                "corregir un importe no convierte la operación en otra distinta");
    }

    @Test
    @DisplayName("Una operación que ya está pero con otro importe detiene la importación")
    void elCambioSePreguntaEnVezDeAplicarse() {
        CsvImportResult result = csvService.importCsv(
                uid, cambiandoElTotal("1600"), ImportMode.ADD);

        assertTrue(result.needsDecision(), "debería pedir una decisión en vez de importar");
        assertFalse(result.ok(), "y mientras no se decida, la importación no está hecha");
        assertEquals(1, result.conflicts().size());

        ImportConflict c = result.conflicts().get(0);
        assertEquals("APPLE", c.ticker());
        assertEquals(1, c.differences().size(),
                () -> "solo el total ha cambiado: " + c.differences());
        assertEquals("Total", c.differences().get(0).field());
        assertEquals("1500", c.differences().get(0).current());
        assertEquals("1600", c.differences().get(0).incoming());

        assertEquals(0, total().compareTo(new BigDecimal("1500")),
                "no se ha tocado nada hasta que el usuario decida");
        assertEquals(1, operationRepo.findByUserId(uid).size(),
                "y desde luego no se ha duplicado");
    }

    @Test
    @DisplayName("Si el usuario elige actualizar, la operación se sustituye")
    void actualizarAplicaLoDelFichero() {
        byte[] csv = cambiandoElTotal("1600");
        String identidad = operationRepo.findByUserId(uid).get(0).getUid();

        CsvImportResult result = csvService.importCsv(uid, csv, ImportMode.ADD, Set.of(identidad));

        assertTrue(result.ok(), () -> "debería completarse: " + result.errors());
        assertEquals(1, operationRepo.findByUserId(uid).size(), "sigue siendo una sola operación");
        assertEquals(0, total().compareTo(new BigDecimal("1600")), "con el importe del fichero");
        assertEquals(identidad, operationRepo.findByUserId(uid).get(0).getUid(),
                "y conservando su identidad, para que el siguiente fichero la siga reconociendo");
    }

    @Test
    @DisplayName("Si el usuario no la elige, la operación se queda como estaba")
    void noElegirLaDejaIntacta() {
        CsvImportResult result = csvService.importCsv(
                uid, cambiandoElTotal("1600"), ImportMode.ADD, Set.of());

        assertTrue(result.ok(), () -> "no decidir nada no es un error: " + result.errors());
        assertEquals(1, operationRepo.findByUserId(uid).size());
        assertEquals(0, total().compareTo(new BigDecimal("1500")),
                "lo que el usuario no ha marcado no se toca");
    }

    /**
     * Los ficheros exportados antes de que existiera la columna se siguen importando, y sus filas
     * entran como operaciones nuevas: es exactamente lo que hacían antes, no una regresión.
     */
    @Test
    @DisplayName("Un fichero sin la columna de identidad se importa como siempre")
    void ficheroAntiguoSinColumnaUid() {
        String antiguo = OperationCsvService.HEADER.replace(";Uid", "") + "\n"
                + "11/03/2025;BUY;TEFONICA;ES0178430E18;ING;100;400;0;GROUP_1;;\n";

        CsvImportResult result = csvService.importCsv(
                uid, antiguo.getBytes(StandardCharsets.UTF_8), ImportMode.ADD);

        assertTrue(result.ok(), () -> "un CSV de once columnas sigue siendo válido: " + result.errors());
        assertEquals(2, operationRepo.findByUserId(uid).size(), "la fila antigua entra como nueva");
        assertTrue(operationRepo.findByUserId(uid).stream()
                        .allMatch(op -> op.getUid() != null && !op.getUid().isBlank()),
                "y al guardarse recibe su identidad, para reconocerla la próxima vez");
    }

    /** El CSV exportado, con el total de la única operación sustituido por otro. */
    private byte[] cambiandoElTotal(String nuevoTotal) {
        return csv().replace(";1500;", ";" + nuevoTotal + ";")
                .getBytes(StandardCharsets.UTF_8);
    }

    private String csv() {
        return new String(csvService.export(uid), StandardCharsets.UTF_8);
    }

    private BigDecimal total() {
        return operationRepo.findByUserId(uid).get(0).getTotal();
    }

    private static OperationForm form(String date, String ticker, String isin,
                                      String qty, String total) {
        OperationForm f = new OperationForm();
        f.setDate(LocalDate.parse(date));
        f.setType(OperationType.BUY);
        f.setTicker(ticker);
        f.setAssetName(isin);
        f.setBroker("ING");
        f.setQuantity(new BigDecimal(qty));
        f.setTotal(new BigDecimal(total));
        f.setCommission(BigDecimal.ZERO);
        f.setAeatGroup(AeatGroup.GROUP_3);
        return f;
    }
}
