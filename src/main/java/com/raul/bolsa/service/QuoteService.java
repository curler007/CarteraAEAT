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
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@Slf4j
public class QuoteService {

    private static final String SEARCH_URL =
            "https://query2.finance.yahoo.com/v1/finance/search?q=%s&quotesCount=1&newsCount=0";
    private static final String CHART_URL =
            "https://query1.finance.yahoo.com/v8/finance/chart/%s?interval=1d&range=5d";

    /** ISINs no estándar que Yahoo Finance no reconoce → símbolo preferido en EUR, fallback en USD */
    private static final Map<String, String[]> ISIN_SYMBOL_OVERRIDE = Map.of(
            "XF000BTC0017", new String[]{"BTC-EUR", "BTC-USD"}
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
                    Optional<QuoteResult> result = fetchPreviousClose(candidate);
                    if (result.isPresent()) {
                        log.debug("ISIN {} → símbolo hardcoded: {}", isin, candidate);
                        return result;
                    }
                }
                return Optional.empty();
            }
            String symbol = resolveSymbol(isin);
            if (symbol == null) return Optional.empty();
            return fetchPreviousClose(symbol);
        } catch (Exception e) {
            log.warn("No se pudo obtener cotización para {}: {}", isin, e.getMessage());
            return Optional.empty();
        }
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

    private Optional<QuoteResult> fetchPreviousClose(String symbol) throws Exception {
        String url = String.format(CHART_URL, symbol);
        String body = rest.exchange(url, HttpMethod.GET, httpEntity(), String.class).getBody();
        if (body == null) return Optional.empty();
        JsonNode meta = mapper.readTree(body).path("chart").path("result").get(0).path("meta");

        // Preferir el precio más actualizado disponible
        double raw = meta.path("regularMarketPrice").asDouble(0);
        if (raw == 0) raw = meta.path("regularMarketPreviousClose").asDouble(0);
        if (raw == 0) raw = meta.path("chartPreviousClose").asDouble(0);
        if (raw == 0) return Optional.empty();

        String currency = meta.path("currency").asText("EUR");

        // GBp = peniques británicos → convertir a GBP dividiendo entre 100
        if ("GBp".equals(currency) || "GBX".equals(currency)) {
            raw = raw / 100.0;
            currency = "GBP";
        }

        if ("EUR".equals(currency)) {
            log.debug("Precio ya en EUR, no se necesita conversión: {} {} → EUR", raw, symbol);
            return Optional.of(new QuoteResult(symbol, BigDecimal.valueOf(raw), "EUR", false));
        }

        // Convertir a EUR via Yahoo Finance forex (ej: USDEUR=X)
        BigDecimal eurRate = fetchForexRate(currency);
        if (eurRate == null) {
            // Devolvemos el precio en divisa original; el frontend mostrará solo el precio
            log.debug("No se pudo obtener tipo de cambio {}EUR, devolviendo precio sin convertir: {} {}", currency, raw, symbol);
            return Optional.of(new QuoteResult(symbol, BigDecimal.valueOf(raw), currency, false));
        }

        BigDecimal priceEur = BigDecimal.valueOf(raw)
                .multiply(eurRate)
                .setScale(4, java.math.RoundingMode.HALF_UP);
        log.debug("Precio convertido a EUR usando tipo de cambio {}EUR = {}: {} {} → {} EUR", currency, eurRate, raw, symbol, priceEur);
        return Optional.of(new QuoteResult(symbol, priceEur, currency, true));
    }

    /** Obtiene el tipo de cambio divisa→EUR más actualizado vía Yahoo Finance (ej: USDEUR=X) */
    private BigDecimal fetchForexRate(String fromCurrency) {
        try {
            String fxSymbol = fromCurrency + "EUR=X";
            String url = String.format(CHART_URL, fxSymbol);
            String body = rest.exchange(url, HttpMethod.GET, httpEntity(), String.class).getBody();
            if (body == null) return null;
            JsonNode meta = mapper.readTree(body).path("chart").path("result").get(0).path("meta");
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
