package com.raul.bolsa.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;
import java.math.BigDecimal;
import java.lang.reflect.Method;
import java.security.cert.X509Certificate;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lectura de la serie de tipos del Data Portal del BCE, sin tocar la red: lo que se comprueba es
 * el formato de la respuesta, que es lo que puede cambiar por debajo.
 */
class EcbFxRateTest {

    /** Respuesta real de {@code EXR/D.USD.EUR.SP00.A?format=csvdata&detail=dataonly}. */
    private static final String CSV = """
            KEY,FREQ,CURRENCY,CURRENCY_DENOM,EXR_TYPE,EXR_SUFFIX,TIME_PERIOD,OBS_VALUE
            EXR.D.USD.EUR.SP00.A,D,USD,EUR,SP00,A,1999-01-04,1.1789
            EXR.D.USD.EUR.SP00.A,D,USD,EUR,SP00,A,2025-09-05,1.1697
            EXR.D.USD.EUR.SP00.A,D,USD,EUR,SP00,A,2026-09-02,1.1578
            """;

    @Test
    @DisplayName("Se queda con la fecha y el tipo, y descarta la cabecera")
    void parsesSeries() {
        Map<LocalDate, BigDecimal> rates = EcbFxRateService.parse(CSV);

        assertEquals(3, rates.size());
        assertEquals(0, new BigDecimal("1.1697").compareTo(rates.get(LocalDate.of(2025, 9, 5))));
        assertEquals(0, new BigDecimal("1.1578").compareTo(rates.get(LocalDate.of(2026, 9, 2))));
    }

    @Test
    @DisplayName("Una respuesta vacía o rota no revienta: devuelve una serie sin datos")
    void toleratesBadInput() {
        assertTrue(EcbFxRateService.parse(null).isEmpty());
        assertTrue(EcbFxRateService.parse("").isEmpty());
        assertTrue(EcbFxRateService.parse("<html>Service unavailable</html>").isEmpty());
    }

    @Test
    @DisplayName("La raíz del BCE va empaquetada y el contexto TLS se puede construir sin red")
    void loadsBundledRootAndBuildsSslContext() throws Exception {
        Method loadRoot = EcbFxRateService.class.getDeclaredMethod("loadRoot");
        loadRoot.setAccessible(true);
        assertNotNull(loadRoot.invoke(null));

        Method buildSslContext = EcbFxRateService.class.getDeclaredMethod("buildSslContext");
        buildSslContext.setAccessible(true);
        SSLContext sslContext = (SSLContext) buildSslContext.invoke(null);
        assertNotNull(sslContext);
    }

    @Test
    @DisplayName("El trust manager combinado expone también la raíz añadida del BCE")
    void combinedTrustManagerExposesBceIssuer() throws Exception {
        X509Certificate root = invoke("loadRoot");
        X509TrustManager jvm = invoke("defaultTrustManager");
        X509TrustManager ecb = invoke("trustManagerFor", root);
        X509TrustManager combined = invoke("combined", jvm, ecb);

        assertTrue(Arrays.asList(combined.getAcceptedIssuers()).contains(root));
    }

    @SuppressWarnings("unchecked")
    private static <T> T invoke(String methodName, Object... args) throws Exception {
        Method method = Arrays.stream(EcbFxRateService.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .filter(candidate -> candidate.getParameterCount() == args.length)
                .filter(candidate -> accepts(candidate.getParameterTypes(), args))
                .findFirst()
                .orElseThrow();
        method.setAccessible(true);
        return (T) method.invoke(null, args);
    }

    private static boolean accepts(Class<?>[] parameterTypes, Object[] args) {
        for (int i = 0; i < parameterTypes.length; i++) {
            if (!parameterTypes[i].isInstance(args[i])) return false;
        }
        return true;
    }
}
