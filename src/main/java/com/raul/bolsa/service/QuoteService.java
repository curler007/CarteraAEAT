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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@Slf4j
public class QuoteService {

    private static final String SEARCH_URL =
            "https://query2.finance.yahoo.com/v1/finance/search?q=%s&quotesCount=1&newsCount=0";
    private static final String CHART_URL =
            "https://query1.finance.yahoo.com/v8/finance/chart/%s?%s";
    /** Query del chart para cotizaciones: la ventana de 5 dias da el cierre de la sesion anterior */
    private static final String QUOTE_CHART_QUERY = "interval=1d&range=5d";

    /** ISINs no estándar que Yahoo Finance no reconoce → símbolo preferido en EUR, fallback en USD */
    private static final Map<String, String[]> ISIN_SYMBOL_OVERRIDE = Map.of(
            "XF000BTC0017", new String[]{"BTC-EUR", "BTC-USD"},
            "US02079K3059", new String[]{"GOOGL"},                       // Alphabet Class A
            "IE00B4ND3602", new String[]{"EGLN.L", "PPFB.SG", "IGLN.L"}  // iShares Physical Gold ETC: EUR (LSE/Stuttgart), fallback USD
    );

    private final RestTemplate rest;
    private final ObjectMapper mapper = new ObjectMapper();

    public QuoteService() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(10_000);
        this.rest = new RestTemplate(factory);
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

    private String resolveSymbol(String isin) throws Exception {
        String url = String.format(SEARCH_URL, isin);
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
        if ("GBp".equals(currency) || "GBX".equals(currency)) {
            raw = raw / 100.0;
            if (prevRaw != null) prevRaw = prevRaw / 100.0;
            currency = "GBP";
        }

        if ("EUR".equals(currency)) {
            log.debug("Precio ya en EUR, no se necesita conversión: {} {} → EUR", raw, symbol);
            return Optional.of(new QuoteResult(symbol, BigDecimal.valueOf(raw), toDecimal(prevRaw), "EUR", false));
        }

        // Convertir a EUR via Yahoo Finance forex (ej: USDEUR=X)
        BigDecimal eurRate = fetchForexRate(currency);
        if (eurRate == null) {
            // Devolvemos el precio en divisa original; el frontend mostrará solo el precio
            log.debug("No se pudo obtener tipo de cambio {}EUR, devolviendo precio sin convertir: {} {}", currency, raw, symbol);
            return Optional.of(new QuoteResult(symbol, BigDecimal.valueOf(raw), toDecimal(prevRaw), currency, false));
        }

        // Ambos precios se convierten al cambio de hoy: la variación diaria refleja así el
        // movimiento del activo, sin mezclarle el movimiento de la divisa.
        BigDecimal priceEur = toEur(BigDecimal.valueOf(raw), eurRate);
        BigDecimal prevEur = prevRaw == null ? null : toEur(BigDecimal.valueOf(prevRaw), eurRate);
        log.debug("Precio convertido a EUR usando tipo de cambio {}EUR = {}: {} {} → {} EUR", currency, eurRate, raw, symbol, priceEur);
        return Optional.of(new QuoteResult(symbol, priceEur, prevEur, currency, true));
    }

    private BigDecimal toEur(BigDecimal amount, BigDecimal eurRate) {
        return amount.multiply(eurRate).setScale(4, java.math.RoundingMode.HALF_UP);
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

    /** Obtiene el tipo de cambio divisa→EUR más actualizado vía Yahoo Finance (ej: USDEUR=X) */
    private BigDecimal fetchForexRate(String fromCurrency) {
        try {
            JsonNode result = fetchChartResult(fromCurrency + "EUR=X", QUOTE_CHART_QUERY).orElse(null);
            if (result == null) return null;
            JsonNode meta = result.path("meta");
            double rate = meta.path("regularMarketPrice").asDouble(0);
            if (rate == 0) rate = meta.path("regularMarketPreviousClose").asDouble(0);
            if (rate == 0) rate = meta.path("chartPreviousClose").asDouble(0);
            return rate == 0 ? null : BigDecimal.valueOf(rate);
        } catch (Exception e) {
            log.warn("No se pudo obtener tipo de cambio {}EUR: {}", fromCurrency, e.getMessage());
            return null;
        }
    }

    private HttpEntity<Void> httpEntity() {
        HttpHeaders h = new HttpHeaders();
        h.set(HttpHeaders.USER_AGENT,
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36");
        h.setAccept(List.of(MediaType.APPLICATION_JSON));
        return new HttpEntity<>(h);
    }
}
