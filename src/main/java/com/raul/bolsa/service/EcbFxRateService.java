package com.raul.bolsa.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
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

    /** Raíz que el BCE usa y la JVM no trae. Vale hasta 2046. */
    private static final String ROOT_CERT = "/certs/sectigo-public-server-auth-root-e46.pem";

    private final RestTemplate rest;

    /** divisa → (fecha → unidades por euro). */
    private final Map<String, Map<LocalDate, BigDecimal>> series = new HashMap<>();

    /** Día del último intento por divisa, con éxito o sin él: no se reintenta en bucle. */
    private final Map<String, LocalDate> lastAttempt = new HashMap<>();

    public EcbFxRateService() {
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
        factory.setReadTimeout(30_000);
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
