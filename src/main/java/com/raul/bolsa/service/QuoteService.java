package com.raul.bolsa.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.raul.bolsa.web.dto.QuoteResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Service
@Slf4j
public class QuoteService {

    private static final String SEARCH_URL =
            "https://query2.finance.yahoo.com/v1/finance/search?q=%s&quotesCount=%d&newsCount=0";
    private static final String CHART_URL =
            "https://query1.finance.yahoo.com/v8/finance/chart/%s?%s";
    /**
     * Query del chart para cotizaciones. Da, en una sola llamada, el cierre de la sesión anterior
     * y los de hace una semana, un mes y un año.
     *
     * <p>La ventana es de dos años y no de uno porque {@code range=1y} arranca justo en la fecha
     * de hace un año: en la práctica su primera sesión cae ya <em>después</em> de esa fecha (los
     * mercados europeos la devuelven siempre así), no hay ningún cierre anterior al que referirse
     * y el periodo anual se quedaba sin referencia de mercado.
     */
    private static final String QUOTE_CHART_QUERY = "interval=1d&range=2y";

    /**
     * Query para valorar la cartera en fechas pasadas. Va aparte de la del dashboard, que se pide
     * una vez por posición en cada carga: aquí interesa alcance y allí ligereza.
     */
    private static final String HISTORIC_CHART_QUERY = "interval=1d&range=10y";


    /** ISINs no estándar que Yahoo Finance no reconoce → símbolo preferido en EUR, fallback en USD */
    private static final Map<String, String[]> ISIN_SYMBOL_OVERRIDE = Map.of(
            "XF000BTC0017", new String[]{"BTC-EUR", "BTC-USD"},
            "US02079K3059", new String[]{"GOOGL"},                       // Alphabet Class A
            "IE00B4ND3602", new String[]{"EGLN.L", "PPFB.SG", "IGLN.L"}  // iShares Physical Gold ETC: EUR (LSE/Stuttgart), fallback USD
    );

    private final RestTemplate rest;
    private final ObjectMapper mapper = new ObjectMapper();
    private final EcbFxRateService fxRates;

    public QuoteService(EcbFxRateService fxRates) {
        this.fxRates = fxRates;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(10_000);
        this.rest = new RestTemplate(factory);
    }

    /**
     * Cotización de un ISIN, probando antes su gemelo si lo tiene.
     *
     * <p>La regla es tajante: si hay gemelo se consulta el gemelo y solo el gemelo. Caer de vuelta
     * al ISIN cuando el gemelo falla sería peor que no cotizar, porque taparía un símbolo mal
     * escrito con un precio de aspecto correcto y nadie se enteraría. Sin precio, en cambio, la
     * fila sale con rayas y el punto del listado en rojo.
     *
     * <p>El gemelo llega como parámetro y no se busca aquí: quien lo guarda necesita cotizar para
     * comprobarlo, y si este servicio fuese a leer los gemelos los dos se llamarían en círculo.
     */
    public Optional<QuoteResult> getQuote(String isin, String twin) {
        if (twin == null || twin.isBlank()) return getQuote(isin);
        try {
            return fetchQuote(twin.trim());
        } catch (Exception e) {
            log.warn("No se pudo cotizar el gemelo {} de {}: {}", twin, isin, e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<QuoteResult> getQuote(String isin) {
        if (!looksLikeIsin(isin)) return Optional.empty();
        try {
            // ISINs con mapeo manual: probar candidatos en orden hasta obtener precio
            if (ISIN_SYMBOL_OVERRIDE.containsKey(isin)) {
                for (String candidate : ISIN_SYMBOL_OVERRIDE.get(isin)) {
                    Optional<QuoteResult> result = fetchQuote(candidate);
                    if (result.isPresent()) {
                        log.debug("ISIN {} → símbolo hardcoded: {}", isin, candidate);
                        return result;
                    }
                }
                return Optional.empty();
            }
            String symbol = resolveSymbol(isin);
            if (symbol == null) return Optional.empty();
            return fetchQuote(symbol);
        } catch (Exception e) {
            log.warn("No se pudo obtener cotización para {}: {}", isin, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Simbolos de Yahoo candidatos para un ISIN, en orden de preferencia: los del mapeo
     * manual si lo hay, y si no el que resuelva la busqueda. Vacio si no resuelve ninguno.
     */
    public List<String> candidateSymbols(String isin) {
        if (!looksLikeIsin(isin)) return List.of();
        String[] override = ISIN_SYMBOL_OVERRIDE.get(isin);
        if (override != null) return List.of(override);
        try {
            String symbol = resolveSymbol(isin);
            return symbol == null ? List.of() : List.of(symbol);
        } catch (Exception e) {
            log.warn("No se pudo resolver el simbolo de {}: {}", isin, e.getMessage());
            return List.of();
        }
    }

    /**
     * Nodo {@code chart.result[0]} de la API de Yahoo para un simbolo.
     * {@code query} es la query string ya construida (intervalo, rango, eventos...).
     */
    public Optional<JsonNode> fetchChartResult(String symbol, String query) throws Exception {
        String url = String.format(CHART_URL, symbol, query);
        String body = rest.exchange(url, HttpMethod.GET, httpEntity(), String.class).getBody();
        if (body == null) return Optional.empty();
        return Optional.ofNullable(mapper.readTree(body).path("chart").path("result").get(0));
    }

    private boolean looksLikeIsin(String s) {
        return s != null && s.matches("[A-Z]{2}[A-Z0-9]{10}");
    }

    /**
     * Lo que Yahoo ofrece al buscar un ISIN, con nombre y mercado para poder distinguirlos.
     *
     * <p>La resolución automática se queda con el primero y por eso falla tanto: en los fondos, el
     * primero suele ser un listado secundario alemán sin histórico y el bueno viene detrás. Esto
     * es para enseñárselos todos a quien tenga que elegir.
     */
    public List<SearchHit> search(String isin, int count) {
        try {
            String url = String.format(SEARCH_URL, isin, count);
            String body = rest.exchange(url, HttpMethod.GET, httpEntity(), String.class).getBody();
            if (body == null) return List.of();
            List<SearchHit> hits = new ArrayList<>();
            for (JsonNode q : mapper.readTree(body).path("quotes")) {
                String symbol = q.path("symbol").asText(null);
                if (symbol == null) continue;
                hits.add(new SearchHit(symbol,
                        firstNonBlank(q.path("longname").asText(null), q.path("shortname").asText(null)),
                        firstNonBlank(q.path("exchDisp").asText(null), q.path("exchange").asText(null))));
            }
            return hits;
        } catch (Exception e) {
            log.warn("No se pudo buscar {} en Yahoo: {}", isin, e.getMessage());
            return List.of();
        }
    }

    /** Un resultado de la búsqueda de Yahoo, sin comprobar todavía si tiene histórico. */
    public record SearchHit(String symbol, String name, String exchange) {}

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        return b == null || b.isBlank() ? null : b;
    }

    private String resolveSymbol(String isin) throws Exception {
        String url = String.format(SEARCH_URL, isin, 1);
        String body = rest.exchange(url, HttpMethod.GET, httpEntity(), String.class).getBody();
        if (body == null) return null;
        JsonNode quotes = mapper.readTree(body).path("quotes");
        if (quotes.isEmpty()) return null;
        String symbol = quotes.get(0).path("symbol").asText(null);
        log.debug("ISIN {} → símbolo Yahoo: {}", isin, symbol);
        return symbol;
    }

    private Optional<QuoteResult> fetchQuote(String symbol) throws Exception {
        JsonNode result = fetchChartResult(symbol, QUOTE_CHART_QUERY).orElse(null);
        if (result == null) return Optional.empty();
        JsonNode meta = result.path("meta");

        // Preferir el precio más actualizado disponible
        double raw = meta.path("regularMarketPrice").asDouble(0);
        // Solo con precio de mercado tiene sentido la variación del día: si caemos al cierre
        // anterior, el "precio actual" ya es ese cierre y la variación saldría siempre cero.
        Double prevRaw = raw == 0 ? null : previousClose(result, raw);
        if (raw == 0) raw = meta.path("regularMarketPreviousClose").asDouble(0);
        if (raw == 0) raw = meta.path("chartPreviousClose").asDouble(0);
        if (raw == 0) return Optional.empty();

        String currency = meta.path("currency").asText("EUR");

        // GBp = peniques británicos → convertir a GBP dividiendo entre 100
        boolean pence = "GBp".equals(currency) || "GBX".equals(currency);
        if (pence) {
            raw = raw / 100.0;
            if (prevRaw != null) prevRaw = prevRaw / 100.0;
            currency = "GBP";
        }

        References refs = references(result).divideBy(pence ? 100 : 1);

        if ("EUR".equals(currency)) {
            log.debug("Precio ya en EUR, no se necesita conversión: {} {} → EUR", raw, symbol);
            return Optional.of(new QuoteResult(symbol, BigDecimal.valueOf(raw), toDecimal(prevRaw),
                    refs.week(), refs.month(), refs.year(), "EUR", false));
        }

        // El precio de hoy y el cierre anterior van los dos al cambio de hoy: en una sesión el
        // euro no se mueve casi, y así la variación diaria refleja el activo sin mezclarle divisa.
        LocalDate today = LocalDate.now();
        BigDecimal priceEur = fxRates.toEur(BigDecimal.valueOf(raw), currency, today).orElse(null);
        if (priceEur == null) {
            // Sin tipo de cambio se devuelve el precio en su divisa; el frontend solo lo muestra
            log.debug("Sin tipo de cambio {}/EUR del BCE, precio sin convertir: {} {}", currency, raw, symbol);
            return Optional.of(new QuoteResult(symbol, BigDecimal.valueOf(raw), toDecimal(prevRaw),
                    refs.week(), refs.month(), refs.year(), currency, false));
        }
        BigDecimal prevEur = prevRaw == null ? null
                : fxRates.toEur(BigDecimal.valueOf(prevRaw), currency, today).orElse(null);

        // Los cierres de referencia, en cambio, van cada uno al cambio de SU fecha. A un año el
        // euro se mueve mucho, y valorar el punto de partida al cambio de hoy contaría el activo
        // pero no lo que le pasó al dinero: un fondo que sube un 10 % en dólares con el dólar
        // cayendo un 5 % deja bastante menos de un 10 % en el bolsillo.
        return Optional.of(new QuoteResult(symbol, priceEur, prevEur,
                refAt(refs.week(), currency, today.minusWeeks(1)),
                refAt(refs.month(), currency, today.minusMonths(1)),
                refAt(refs.year(), currency, today.minusYears(1)),
                currency, true));
    }

    /**
     * Abre una sesión para valorar la cartera en fechas pasadas.
     *
     * <p>Existe para cachear: valorar tres periodos pregunta por los mismos valores tres veces, y
     * cada consulta a Yahoo cuesta una resolución de símbolo más una serie. Con la sesión, cada
     * valor se resuelve y se descarga una sola vez, sirva para las fechas que sirva.
     */
    public Historic openHistoric() {
        return new Historic();
    }

    /** Un importe con su divisa, para poder compararlo sin confundir euros con dólares. */
    public record Money(BigDecimal amount, String currency) {}

    /** Serie histórica que Yahoo publica de un valor: bajo qué símbolo y desde cuándo. */
    public record Series(String symbol, LocalDate from) {}

    /** Ventana de consulta histórica con memoria de lo ya pedido. No es segura entre hilos. */
    public class Historic {

        private final Map<String, List<String>> symbols = new HashMap<>();
        private final Map<String, JsonNode> charts = new HashMap<>();

        /** Primer símbolo al que Yahoo resuelve el ISIN, o null si no resuelve a ninguno. */
        public String symbolFor(String isin) {
            List<String> found = symbols.computeIfAbsent(isin, QuoteService.this::candidateSymbols);
            return found.isEmpty() ? null : found.get(0);
        }

        /**
         * Serie histórica utilizable de un ISIN, si Yahoo publica alguna.
         *
         * <p>Se exige más de un cierre a propósito. Yahoo resuelve muchos fondos a un listado
         * secundario de bolsa alemana que devuelve el precio de hoy y nada más: con un único punto
         * la posición se ve bien en la tabla y en cambio no hay con qué calcular ninguna variación.
         * Una serie de un punto no es una serie.
         *
         * <p>No se compara contra la fecha de compra. Que Yahoo empiece más tarde que la compra es
         * normal en valores antiguos —Apple comprada en 2000, Telefónica en 2011— y no es un ISIN
         * equivocado, que es lo que esto busca destapar.
         */
        public Optional<Series> seriesOf(String isin) {
            return firstUsable(symbols.computeIfAbsent(isin, QuoteService.this::candidateSymbols));
        }

        /** Igual, pero sobre un símbolo dado a mano: es como se comprueba un gemelo. */
        public Optional<Series> seriesOfSymbol(String symbol) {
            return firstUsable(List.of(symbol));
        }

        private Optional<Series> firstUsable(List<String> candidates) {
            for (String symbol : candidates) {
                JsonNode result = charts.computeIfAbsent(symbol, this::chart);
                if (result == null) continue;
                List<LocalDate> days = tradingDays(result);
                if (days.size() < 2) continue;
                return Optional.of(new Series(symbol, days.get(0)));
            }
            return Optional.empty();
        }

        /**
         * Pasa un importe a euros con el tipo del BCE de esa fecha. Hace falta para comparar
         * precios de listados que cotizan en monedas distintas, que es lo normal entre listados
         * del mismo fondo.
         *
         * <p>Convierte el importe en vez de devolver el tipo a propósito: el del BCE va en
         * unidades por euro y el de Yahoo iba en euros por unidad, así que un método que
         * devolviera "el cambio" invita a multiplicar cuando toca dividir.
         */
        public Optional<BigDecimal> toEurAt(BigDecimal amount, String currency, LocalDate date) {
            return fxRates.toEur(amount, currency, date);
        }

        /**
         * Precio actual de un símbolo, en su divisa.
         *
         * <p>Sale del mismo gráfico que ya se descargó para mirar el histórico, así que no cuesta
         * ninguna llamada más. Sirve para comparar listados entre sí: dos listados del mismo fondo
         * cotizan casi igual, y uno que no lo sea canta a la legua.
         */
        public Optional<Money> priceOf(String symbol) {
            JsonNode result = charts.computeIfAbsent(symbol, this::chart);
            if (result == null) return Optional.empty();
            JsonNode meta = result.path("meta");
            double raw = meta.path("regularMarketPrice").asDouble(0);
            if (raw == 0) raw = meta.path("regularMarketPreviousClose").asDouble(0);
            if (raw == 0) raw = meta.path("chartPreviousClose").asDouble(0);
            if (raw == 0) return Optional.empty();
            String currency = meta.path("currency").asText("EUR");
            if ("GBp".equals(currency) || "GBX".equals(currency)) {
                return Optional.of(new Money(BigDecimal.valueOf(raw / 100.0), "GBP"));
            }
            return Optional.of(new Money(BigDecimal.valueOf(raw), currency));
        }

        private List<LocalDate> tradingDays(JsonNode result) {
            JsonNode stamps = result.path("timestamp");
            JsonNode closes = result.path("indicators").path("quote").path(0).path("close");
            List<LocalDate> days = new ArrayList<>();
            for (int i = 0; i < stamps.size() && i < closes.size(); i++) {
                if (closes.get(i).isNumber() && closes.get(i).asDouble() > 0) {
                    days.add(Instant.ofEpochSecond(stamps.get(i).asLong())
                            .atZone(ZoneOffset.UTC).toLocalDate());
                }
            }
            return days;
        }

        private JsonNode chart(String symbol) {
            try {
                return fetchChartResult(symbol, HISTORIC_CHART_QUERY).orElse(null);
            } catch (Exception e) {
                log.warn("No se pudo obtener el histórico de {}: {}", symbol, e.getMessage());
                return null;
            }
        }
    }

    /** Cierres de referencia de los periodos que muestra el dashboard, en divisa original. */
    private record References(BigDecimal week, BigDecimal month, BigDecimal year) {
        References divideBy(int divisor) {
            if (divisor == 1) return this;
            BigDecimal d = BigDecimal.valueOf(divisor);
            return new References(split(week, d), split(month, d), split(year, d));
        }

        private static BigDecimal split(BigDecimal v, BigDecimal d) {
            return v == null ? null : v.divide(d, 6, java.math.RoundingMode.HALF_UP);
        }
    }

    private References references(JsonNode result) {
        LocalDate today = LocalDate.now();
        return new References(
                closeOn(result, today.minusWeeks(1)),
                closeOn(result, today.minusMonths(1)),
                closeOn(result, today.minusYears(1)));
    }

    /**
     * Último cierre publicado en {@code target} o antes. Devuelve null si la serie empieza más
     * tarde: el valor no cotizaba entonces y ese periodo no tiene referencia de mercado.
     */
    private BigDecimal closeOn(JsonNode result, LocalDate target) {
        JsonNode stamps = result.path("timestamp");
        JsonNode closes = result.path("indicators").path("quote").path(0).path("close");
        BigDecimal last = null;
        for (int i = 0; i < stamps.size() && i < closes.size(); i++) {
            LocalDate day = Instant.ofEpochSecond(stamps.get(i).asLong())
                    .atZone(ZoneOffset.UTC).toLocalDate();
            if (day.isAfter(target)) break;
            if (closes.get(i).isNumber() && closes.get(i).asDouble() > 0) {
                last = BigDecimal.valueOf(closes.get(i).asDouble());
            }
        }
        return last;
    }


    private BigDecimal toDecimal(Double value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    /**
     * Cierre de la sesión anterior a la que refleja {@code price}, en divisa original.
     *
     * <p>Yahoo no lo expone en meta con {@code range=5d}: {@code chartPreviousClose} es el cierre
     * previo a toda la ventana de 5 días, no el de la sesión anterior. Se toma por tanto de la
     * serie diaria, y como respaldo se deriva de {@code regularMarketChangePercent}.
     */
    private Double previousClose(JsonNode result, double price) {
        List<Double> closes = new ArrayList<>();
        for (JsonNode c : result.path("indicators").path("quote").path(0).path("close")) {
            if (c.isNumber() && c.asDouble() > 0) closes.add(c.asDouble());
        }
        if (closes.size() >= 2) {
            // El último punto es la sesión en curso cuando su cierre coincide con el precio actual
            // (con holgura: la serie llega con menos precisión que meta), y entonces el cierre
            // anterior es el penúltimo. Si la serie aún no incluye la sesión en curso, el último
            // cierre ya es el anterior.
            int last = closes.size() - 1;
            boolean lastIsCurrent = Math.abs(closes.get(last) - price) <= Math.abs(price) * 1e-3;
            return closes.get(lastIsCurrent ? last - 1 : last);
        }
        JsonNode changePercent = result.path("meta").path("regularMarketChangePercent");
        if (changePercent.isNumber()) {
            double ratio = 1 + changePercent.asDouble() / 100.0;
            if (ratio > 0) return price / ratio;
        }
        return null;
    }

    /**
     * Un cierre de referencia pasado a euros al cambio de su propia fecha, con el tipo del BCE,
     * que es el mismo que se usó al convertir el coste de adquisición al importar. Con el de
     * Yahoo, coste y valoración del mismo día salían de dos fuentes distintas.
     *
     * <p>Devuelve null si falta el cierre o el tipo: sin uno de los dos ese periodo no tiene punto
     * de partida, y el dashboard ya sabe que un nulo significa "sin referencia".
     */
    private BigDecimal refAt(BigDecimal close, String currency, LocalDate date) {
        if (close == null) return null;
        return fxRates.toEur(close, currency, date).orElse(null);
    }

    private HttpEntity<Void> httpEntity() {
        HttpHeaders h = new HttpHeaders();
        h.set(HttpHeaders.USER_AGENT,
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36");
        h.setAccept(List.of(MediaType.APPLICATION_JSON));
        return new HttpEntity<>(h);
    }
}
