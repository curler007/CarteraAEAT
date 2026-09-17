package com.raul.bolsa.service;

import com.raul.bolsa.domain.FxRate;
import com.raul.bolsa.repository.FxRateRepository;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Los tipos del BCE guardados valen sin red.
 *
 * <p>Es el motivo de la tabla: con la serie en memoria y nada más, un reinicio obligaba a
 * descargar veintisiete años otra vez, y un BCE caído dejaba los precios sin convertir. Ninguno
 * de estos tests toca la red —solo hidratan desde la base— y eso es parte de lo que comprueban.
 */
@SpringBootTest
class FxRateStoreTest {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) throws IOException {
        Path db = Files.createTempDirectory("bolsa-fx-").resolve("test.db");
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + db.toAbsolutePath());
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
        registry.add("app.security.username", () -> "fx");
        registry.add("app.security.password", () -> "fx");
    }

    @Autowired FxRateRepository repo;
    @Autowired EcbFxRateService fx;

    private static final LocalDate VIERNES = LocalDate.parse("2026-09-11");

    @Test
    @DisplayName("Con la serie guardada se convierte sin pedir nada al BCE")
    void convertsFromTheStoredSeries() {
        repo.save(new FxRate("USD", VIERNES, new BigDecimal("1.17")));
        fx.hydrate();

        assertEquals(0, new BigDecimal("1.17").compareTo(fx.rate("USD", VIERNES).orElseThrow()),
                "el tipo guardado es el que vale");
        assertEquals(0, new BigDecimal("100").compareTo(
                        fx.toEur(new BigDecimal("117"), "USD", VIERNES).orElseThrow()),
                "117 dólares a 1,17 son 100 euros");
    }

    @Test
    @DisplayName("El fin de semana vale el último tipo publicado")
    void weekendFallsBackToTheLastPublishedRate() {
        repo.save(new FxRate("GBP", VIERNES, new BigDecimal("0.86")));
        fx.hydrate();

        // El BCE no publica sábado ni domingo: el tipo del viernes es el vigente.
        assertTrue(fx.rate("GBP", VIERNES.plusDays(2)).isPresent(),
                "el domingo debe resolverse con el viernes");
        assertTrue(fx.rate("GBP", VIERNES.plusDays(30)).isEmpty(),
                "pero un mes después ya no: convertir con un tipo rancio sería inventar");
    }

    @Test
    @DisplayName("Una divisa que no está no se espera: se devuelve vacío y se encarga la descarga")
    void unknownCurrencyDoesNotBlock() {
        long antes = System.currentTimeMillis();
        Optional<BigDecimal> tipo = fx.rate("JPY", VIERNES);
        long tardo = System.currentTimeMillis() - antes;

        assertTrue(tipo.isEmpty(), "sin dato guardado no hay tipo que dar");
        // Lo que se está comprobando es que no ha esperado a la red: la descarga va a otro hilo.
        // Con el timeout de lectura en 5 s, una llamada síncrona no bajaría de ahí.
        assertTrue(tardo < 1_000, "no debe esperar a la red: tardó " + tardo + " ms");
    }

    @Test
    @DisplayName("La primera vez se pide la serie entera; después, solo los días nuevos")
    void asksOnlyForWhatIsMissing() {
        assertTrue(EcbFxRateService.seriesUrl("USD", null).endsWith("detail=dataonly"),
                "sin nada guardado hay que traer la serie desde 1999");
        assertTrue(EcbFxRateService.seriesUrl("USD", VIERNES).endsWith("&startPeriod=2026-09-11"),
                "con la serie al día basta con pedir desde el último día conocido");
    }
}
