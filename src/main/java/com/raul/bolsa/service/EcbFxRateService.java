package com.raul.bolsa.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Tipos de cambio oficiales del BCE, que es el criterio que admite Hacienda para valorar en euros
 * una operación hecha en otra divisa.
 *
 * <p>Se descarga de una vez la serie diaria completa de la divisa —desde 1999, unos 400 KB— en vez
 * de pedir fecha a fecha: al importar un extracto hacen falta decenas de fechas distintas, y la
 * serie entera cabe de sobra en memoria. Se guarda hasta el final del día, que es cuando el BCE
 * publica el siguiente dato, sobre las 16:00 CET.
 *
 * <p>La fuente es el Data Portal ({@code data-api.ecb.europa.eu}) y no el fichero
 * {@code eurofxref-hist.csv} de la web del BCE: ese está detrás de un certificado emitido por una
 * raíz de Sectigo que no llevan los almacenes de confianza de Java, así que desde la JVM no se
 * puede descargar aunque el navegador lo abra sin problemas.
 *
 * <p>El BCE no publica los fines de semana ni los festivos, así que si la fecha pedida no está se
 * retrocede hasta la última publicada, que es el tipo vigente ese día.
 */
@Service
@Slf4j
public class EcbFxRateService {

    /** Serie diaria de tipos de referencia de una divisa contra el euro. */
    private static final String SERIES_URL = "https://data-api.ecb.europa.eu/service/data/EXR/"
            + "D.%s.EUR.SP00.A?format=csvdata&detail=dataonly";

    /** Columnas de la respuesta con {@code detail=dataonly}. */
    private static final int COL_DATE = 6;
    private static final int COL_VALUE = 7;

    /** Días hacia atrás que se aceptan al buscar el tipo: cubre puentes y cierres largos. */
    private static final int MAX_LOOKBACK_DAYS = 10;

    private static final int SCALE = 6;

    private final RestTemplate rest;

    /** divisa → (fecha → unidades por euro). */
    private final Map<String, Map<LocalDate, BigDecimal>> series = new HashMap<>();

    /** Día del último intento por divisa, con éxito o sin él: no se reintenta en bucle. */
    private final Map<String, LocalDate> lastAttempt = new HashMap<>();

    public EcbFxRateService() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(30_000);
        this.rest = new RestTemplate(factory);
    }

    /**
     * Convierte un importe a euros con el tipo del BCE de esa fecha.
     *
     * @return vacío si no hay serie disponible o la fecha queda fuera de ella; nunca un importe
     *         inventado, porque un coste de adquisición mal convertido no se detecta después
     */
    public Optional<BigDecimal> toEur(BigDecimal amount, String currency, LocalDate date) {
        if (amount == null || currency == null) return Optional.empty();
        if (currency.equalsIgnoreCase("EUR")) return Optional.of(amount);

        return rate(currency, date).map(r -> amount.divide(r, SCALE, RoundingMode.HALF_UP));
    }

    /** Unidades de {@code currency} que compra un euro en esa fecha. */
    public Optional<BigDecimal> rate(String currency, LocalDate date) {
        Map<LocalDate, BigDecimal> rates = load(currency.toUpperCase());

        for (int back = 0; back <= MAX_LOOKBACK_DAYS; back++) {
            BigDecimal r = rates.get(date.minusDays(back));
            if (r != null) return Optional.of(r);
        }
        return Optional.empty();
    }

    private synchronized Map<LocalDate, BigDecimal> load(String currency) {
        LocalDate today = LocalDate.now();
        if (today.equals(lastAttempt.get(currency))) {
            return series.getOrDefault(currency, Map.of());
        }
        lastAttempt.put(currency, today);

        try {
            String csv = rest.getForObject(String.format(SERIES_URL, currency), String.class);
            Map<LocalDate, BigDecimal> parsed = parse(csv);
            if (!parsed.isEmpty()) {
                series.put(currency, parsed);
                log.info("Serie de tipos {}/EUR del BCE cargada: {} días", currency, parsed.size());
            }
        } catch (Exception e) {
            // Con la serie de un día anterior en memoria se sigue trabajando; sin ella, quien
            // llame recibirá un Optional vacío y podrá avisar en vez de convertir a ciegas.
            log.warn("No se ha podido descargar la serie {}/EUR del BCE: {}", currency, e.getMessage());
        }
        return series.getOrDefault(currency, Map.of());
    }

    /** Una fila por día: {@code EXR.D.USD.EUR.SP00.A,D,USD,EUR,SP00,A,1999-01-04,1.1789}. */
    static Map<LocalDate, BigDecimal> parse(String csv) {
        Map<LocalDate, BigDecimal> out = new HashMap<>();
        if (csv == null || csv.isBlank()) return out;

        for (String line : csv.split("\r?\n")) {
            String[] cells = line.split(",", -1);
            if (cells.length <= COL_VALUE) continue;
            try {
                out.put(LocalDate.parse(cells[COL_DATE].trim()),
                        new BigDecimal(cells[COL_VALUE].trim()));
            } catch (Exception ignored) {
                // Cabecera o día sin cotizar: se salta, el lookback busca el anterior.
            }
        }
        return out;
    }
}
