package com.raul.bolsa.service;

import com.raul.bolsa.domain.FxRate;
import com.raul.bolsa.repository.FxRateRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tipos de cambio oficiales del BCE, que es el criterio que admite Hacienda para valorar en euros
 * una operación hecha en otra divisa.
 *
 * <p>Se descarga de una vez la serie diaria completa de la divisa —desde 1999, unos 400 KB— en vez
 * de pedir fecha a fecha: al importar un extracto hacen falta decenas de fechas distintas, y la
 * serie entera cabe de sobra en memoria. A partir de ahí solo se piden los días nuevos, con
 * {@code startPeriod}.
 *
 * <p>La serie se guarda en {@link com.raul.bolsa.domain.FxRate}, y la memoria es solo la copia
 * caliente que se hidrata al arrancar. Eso cambia dos cosas que se notaban: el reinicio ya no
 * obliga a descargar veintisiete años otra vez, y con el BCE caído se sigue convirtiendo por el
 * último día guardado.
 *
 * <p><b>Ninguna petición web espera al BCE.</b> {@link #toEur} responde con lo que hay en memoria y,
 * si falta, encarga la descarga a un hilo aparte y devuelve vacío para que la página lo diga. La
 * descarga dentro de la petición fue lo que tostó el dashboard el 17/09/2026: veinte cotizaciones
 * en paralelo, cada una con su conexión JDBC retenida, todas encoladas tras un timeout de 30 s
 * contra un BCE que no respondía, y el pool de conexiones agotado. Importar es la excepción y
 * tiene su propio método: ver {@link #toEurBlocking}.
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

    /** Raíz que el BCE usa y la JVM no trae. Vale hasta 2046. */
    private static final String ROOT_CERT = "/certs/sectigo-public-server-auth-root-e46.pem";

    /**
     * Lo que se espera a la red cuando toca esperar.
     *
     * <p>Treinta segundos eran un cuelgue mientras la descarga vivía dentro de la petición web;
     * ahora que va en segundo plano, el problema es el contrario: la primera vez hay que bajarse
     * la serie entera desde 1999, unos 400 KB, y con cinco segundos no siempre llegaba. Cuando
     * eso falla no hay tipos guardados, y lo que el usuario ve es la cartera sin valorar.
     */
    private static final int READ_TIMEOUT_MS = 15_000;

    private final RestTemplate rest;
    private final FxRateRepository repo;

    /** divisa → (fecha → unidades por euro). Copia caliente de la tabla. */
    private final Map<String, Map<LocalDate, BigDecimal>> series = new ConcurrentHashMap<>();

    /** Día del último intento por divisa, con éxito o sin él: no se reintenta en bucle. */
    private final Map<String, LocalDate> lastAttempt = new ConcurrentHashMap<>();

    /**
     * Un solo hilo, en segundo plano y en cola: dos divisas que falten a la vez se descargan una
     * detrás de otra sin que nadie las espere, y ninguna petición web se bloquea por ellas.
     */
    private final ExecutorService refresher = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "fx-refresh");
        t.setDaemon(true);
        return t;
    });

    public EcbFxRateService(FxRateRepository repo) {
        this.repo = repo;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod)
                    throws IOException {
                super.prepareConnection(connection, httpMethod);
                if (connection instanceof HttpsURLConnection https) {
                    https.setSSLSocketFactory(SSL.getSocketFactory());
                }
            }
        };
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(READ_TIMEOUT_MS);
        this.rest = new RestTemplate(factory);
    }

    /**
     * Contexto TLS que añade la raíz del BCE a las que ya trae la JVM.
     *
     * <p>El BCE sirve sus certificados bajo <em>Sectigo Public Server Authentication Root E46</em>,
     * una raíz de 2021 que no está en el almacén de confianza de ningún JDK reciente —comprobado
     * en el 17 y en el 23—, así que desde Java el handshake falla con {@code PKIX path building
     * failed} aunque el navegador y curl abran la misma URL sin pestañear. Los tres endpoints del
     * BCE usan la misma raíz y el antiguo SDW ya no responde, así que no hay a dónde mudarse.
     *
     * <p>Se añade solo para este cliente. Tocar el contexto TLS por defecto afectaría también a
     * las llamadas a Yahoo, que no tienen por qué heredar una confianza que no necesitan.
     */
    private static final SSLContext SSL = buildSslContext();

    private static SSLContext buildSslContext() {
        try {
            X509TrustManager jvm = defaultTrustManager();
            X509TrustManager ecb = trustManagerFor(loadRoot());
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{combined(jvm, ecb)}, null);
            return ctx;
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo preparar la confianza del BCE", e);
        }
    }

    /** Acepta lo que acepte la JVM y, si no, lo que firme la raíz del BCE. */
    private static X509TrustManager combined(X509TrustManager jvm, X509TrustManager ecb) {
        return new X509TrustManager() {
            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType)
                    throws CertificateException {
                try {
                    jvm.checkServerTrusted(chain, authType);
                } catch (CertificateException notInJvm) {
                    ecb.checkServerTrusted(chain, authType);
                }
            }

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType)
                    throws CertificateException {
                jvm.checkClientTrusted(chain, authType);
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return concat(jvm.getAcceptedIssuers(), ecb.getAcceptedIssuers());
            }
        };
    }

    private static X509Certificate[] concat(X509Certificate[] left, X509Certificate[] right) {
        X509Certificate[] both = new X509Certificate[left.length + right.length];
        System.arraycopy(left, 0, both, 0, left.length);
        System.arraycopy(right, 0, both, left.length, right.length);
        return both;
    }

    private static X509Certificate loadRoot() throws Exception {
        try (InputStream pem = EcbFxRateService.class.getResourceAsStream(ROOT_CERT)) {
            if (pem == null) throw new IllegalStateException("falta " + ROOT_CERT);
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(pem);
        }
    }

    /**
     * Confianza construida solo sobre la raíz del BCE. Su almacén contiene esa raíz y nada más, de
     * modo que ya la expone como emisor aceptado: envolverlo para añadirla otra vez la duplicaba.
     */
    private static X509TrustManager trustManagerFor(X509Certificate root) throws Exception {
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, null);
        store.setCertificateEntry("ecb-root", root);
        return firstX509(store);
    }

    private static X509TrustManager defaultTrustManager() throws Exception {
        return firstX509(null);
    }

    private static X509TrustManager firstX509(KeyStore store) throws Exception {
        TrustManagerFactory tmf =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(store);
        for (TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof X509TrustManager x509) return x509;
        }
        throw new IllegalStateException("sin gestor de confianza X509");
    }

    /**
     * Convierte un importe a euros con el tipo del BCE de esa fecha.
     *
     * @return vacío si no hay serie disponible o la fecha queda fuera de ella; nunca un importe
     *         inventado, porque un coste de adquisición mal convertido no se detecta después
     */
    public Optional<BigDecimal> toEur(BigDecimal amount, String currency, LocalDate date) {
        return convert(amount, currency, date, true);
    }

    /**
     * Igual, pero sin bajar nada: si el tipo no está guardado, devuelve vacío y encarga la
     * descarga a un hilo aparte.
     *
     * <p>Es el camino de las páginas, y en particular el de las cotizaciones, que se piden a razón
     * de una por posición y en paralelo. Con {@link #toEur} ahí, un BCE que no responde encola
     * veinte peticiones —cada una con su conexión JDBC retenida— detrás del mismo timeout y agota
     * el pool: es lo que dejó el dashboard muerto el 17/09/2026. Al importar, en cambio, sí se
     * espera: un extracto sin convertir se rechaza entero, y ahí unos segundos valen la pena.
     */
    public Optional<BigDecimal> toEurCached(BigDecimal amount, String currency, LocalDate date) {
        return convert(amount, currency, date, false);
    }

    private Optional<BigDecimal> convert(BigDecimal amount, String currency, LocalDate date,
                                         boolean waitForDownload) {
        if (amount == null || currency == null) return Optional.empty();
        if (currency.equalsIgnoreCase("EUR")) return Optional.of(amount);

        return rate(currency, date, waitForDownload)
                .map(r -> amount.divide(r, SCALE, RoundingMode.HALF_UP));
    }

    /** Unidades de {@code currency} que compra un euro en esa fecha, sin esperar a la red. */
    public Optional<BigDecimal> rate(String currency, LocalDate date) {
        return rate(currency, date, false);
    }

    private Optional<BigDecimal> rate(String currency, LocalDate date, boolean waitForDownload) {
        String iso = currency.toUpperCase();
        Optional<BigDecimal> found = lookup(iso, date);
        if (found.isPresent()) return found;

        // Puede ser que la divisa no se haya visto nunca, o que la serie se haya quedado corta.
        // En los dos casos hay que pedirla; lo que cambia es si quien pregunta puede esperar.
        if (waitForDownload) {
            // force: si el intento de hoy ya falló, quien importa tiene derecho a reintentar. Con
            // el BCE recuperado, volver a subir el fichero debe funcionar y no fallar hasta mañana.
            refresh(iso, true);
            return lookup(iso, date);
        }
        refreshLater(iso);
        return Optional.empty();
    }

    /** El tipo vigente en esa fecha según lo que ya está en memoria. */
    private Optional<BigDecimal> lookup(String currency, LocalDate date) {
        Map<LocalDate, BigDecimal> rates = series.getOrDefault(currency, Map.of());
        for (int back = 0; back <= MAX_LOOKBACK_DAYS; back++) {
            BigDecimal r = rates.get(date.minusDays(back));
            if (r != null) return Optional.of(r);
        }
        return Optional.empty();
    }

    /**
     * Sube a memoria lo que ya está guardado. Se hace una vez al arrancar, y es lo que permite
     * responder sin red: la serie de ayer sigue siendo válida para convertir hoy.
     */
    @EventListener(ApplicationReadyEvent.class)
    void hydrateAndRefresh() {
        hydrate();
        // Al arrancar se piden los días que falten de lo que ya se conoce, en segundo plano: la
        // primera visita no tiene por qué pagar la descarga.
        series.keySet().forEach(this::refreshLater);
    }

    /** Solo la parte que lee la tabla, sin red: es lo que hace útil el arranque en frío. */
    void hydrate() {
        Map<String, Map<LocalDate, BigDecimal>> loaded = new HashMap<>();
        for (FxRate row : repo.findAllByOrderByCurrencyAscRateDateAsc()) {
            loaded.computeIfAbsent(row.getCurrency(), k -> new HashMap<>())
                    .put(row.getRateDate(), row.getRate());
        }
        series.putAll(loaded);
        loaded.forEach((currency, rates) ->
                log.info("Serie de tipos {}/EUR en base de datos: {} días", currency, rates.size()));
    }

    /**
     * Los días nuevos de cada divisa conocida. El BCE publica sobre las 16:00 CET, así que a y
     * media ya está; si el NAS estaba apagado a esa hora, lo coge el arranque siguiente.
     */
    @Scheduled(cron = "0 30 16 * * MON-FRI", zone = "Europe/Madrid")
    void refreshDaily() {
        series.keySet().forEach(this::refreshLater);
    }

    /** Encola la descarga sin esperarla, como máximo un intento por divisa y día. */
    private void refreshLater(String currency) {
        if (LocalDate.now().equals(lastAttempt.get(currency))) return;
        refresher.submit(() -> refresh(currency, false));
    }

    /**
     * Pide al BCE los días que faltan y los guarda. Solo trae desde el último día conocido, así
     * que después de la primera vez son cuatro filas y no veintisiete años.
     */
    private synchronized void refresh(String currency, boolean force) {
        LocalDate today = LocalDate.now();
        if (!force && today.equals(lastAttempt.get(currency))) return;
        lastAttempt.put(currency, today);

        Map<LocalDate, BigDecimal> known = series.getOrDefault(currency, Map.of());
        LocalDate from = known.keySet().stream().max(LocalDate::compareTo).orElse(null);
        try {
            Map<LocalDate, BigDecimal> parsed = parse(
                    rest.getForObject(seriesUrl(currency, from), String.class));
            Map<LocalDate, BigDecimal> nuevos = new HashMap<>(parsed);
            nuevos.keySet().removeAll(known.keySet());
            if (nuevos.isEmpty()) return;

            repo.saveAll(nuevos.entrySet().stream()
                    .map(e -> new FxRate(currency, e.getKey(), e.getValue()))
                    .toList());
            Map<LocalDate, BigDecimal> merged = new HashMap<>(known);
            merged.putAll(nuevos);
            series.put(currency, merged);
            log.info("Serie de tipos {}/EUR del BCE: {} días nuevos, {} en total",
                    currency, nuevos.size(), merged.size());
        } catch (Exception e) {
            // Con lo guardado se sigue convirtiendo; sin nada, quien llame recibe un Optional
            // vacío y podrá avisar en vez de convertir a ciegas.
            log.warn("No se ha podido descargar la serie {}/EUR del BCE: {}", currency, e.getMessage());
        }
    }

    /** La serie entera la primera vez, y desde el último día conocido las siguientes. */
    static String seriesUrl(String currency, LocalDate from) {
        String url = String.format(SERIES_URL, currency);
        return from == null ? url : url + "&startPeriod=" + from;
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
