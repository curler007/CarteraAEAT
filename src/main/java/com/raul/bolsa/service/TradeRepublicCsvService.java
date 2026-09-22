package com.raul.bolsa.service;

import com.raul.bolsa.domain.AeatGroup;
import com.raul.bolsa.domain.Operation;
import com.raul.bolsa.domain.OperationType;
import com.raul.bolsa.web.dto.OperationForm;
import com.raul.bolsa.web.dto.TradeRepublicParseResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Traduce la "Exportación de transacción" de Trade Republic a operaciones de la cartera.
 *
 * <p>Del fichero interesan las compras y ventas de valores ({@code category=TRADING}) y las
 * entregas gratuitas ({@code FREE_RECEIPT}), que son acciones que entran en la cartera sin
 * contrapartida en efectivo: traspasos desde otro broker o recompensas. Estas últimas se dan de
 * alta con coste cero y se devuelven aparte para poder pedir al usuario que las valore, porque
 * un coste cero convertiría toda la venta futura en ganancia.
 *
 * <p>El resto de movimientos —ingresos, dividendos, intereses, promociones— no altera ninguna
 * posición y se ignora, contándolo para poder informar de cuántos se han dejado fuera.
 *
 * <p>Cada operación importada guarda en las notas el {@code transaction_id} de origen, de forma
 * que reimportar un fichero que solapa con lo ya cargado no duplica nada: Trade Republic exporta
 * siempre el histórico completo, así que el solapamiento es el caso normal, no la excepción.
 *
 * <p>Las operaciones que el usuario ya había metido a mano no tienen ese identificador, así que
 * para ellas el reconocimiento va por contenido; de eso se encarga {@link ManualOverlap}.
 */
@Service
@Slf4j
public class TradeRepublicCsvService {

    /** Broker asignado a todas las operaciones importadas de este fichero. */
    public static final String BROKER = "Trade Republic";

    /** Prefijo con el que se marca en las notas el id de la transacción de origen. */
    public static final String NOTE_PREFIX = "TR:";

    /**
     * Tipos oficiales del BCE. Solo se usan si alguna liquidación llegara en otra divisa, que en
     * este fichero no pasa: Trade Republic liquida en la divisa de la cuenta.
     */
    private final EcbFxRateService fxRates;

    public TradeRepublicCsvService(EcbFxRateService fxRates) {
        this.fxRates = fxRates;
    }

    /** Coletilla en las notas de las entregas recibidas sin contrapartida en efectivo. */
    public static final String NOTE_FREE_RECEIPT = "traspaso recibido sin coste";

    /** Entrega de valores sin pago: entran acciones, pero el fichero no dice a qué precio. */
    private static final String FREE_RECEIPT = "FREE_RECEIPT";

    /** Columnas que identifican inequívocamente el fichero de Trade Republic. */
    private static final List<String> SIGNATURE = List.of("datetime", "asset_class", "transaction_id");

    private static final char SEP = ',';

    private static final DateTimeFormatter DESCRIPTION_DATE =
            DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /**
     * Las criptomonedas no tienen ISIN: Trade Republic pone el símbolo. Se traduce al
     * identificador sintético que ya usa la aplicación para poder cotizarlas.
     */
    private static final Map<String, String> CRYPTO_ISIN = Map.of("BTC", "XF000BTC0017");

    /** ¿La cabecera es la de una exportación de Trade Republic? */
    public static boolean matches(List<String> header) {
        List<String> lower = header.stream().map(h -> h.trim().toLowerCase()).toList();
        return lower.containsAll(SIGNATURE);
    }

    /** Separador de este formato, para poder reutilizar el lector CSV genérico. */
    public static char separator() {
        return SEP;
    }

    /**
     * Convierte las filas del fichero (cabecera incluida) en operaciones listas para guardar.
     *
     * @param existingOnly operaciones del usuario contra las que resolver ticker, grupo AEAT y
     *                     duplicados; vacío cuando se va a reemplazar la cartera entera
     */
    public TradeRepublicParseResult parse(List<List<String>> rows, List<Operation> existingOnly) {
        Map<String, Integer> col = columnIndex(rows.get(0));

        Map<String, String> tickerByIsin = new HashMap<>();
        Map<String, AeatGroup> groupByIsin = new HashMap<>();
        Set<String> importedIds = new HashSet<>();
        ManualOverlap manual = new ManualOverlap();
        for (Operation op : existingOnly) {
            tickerByIsin.putIfAbsent(op.getAssetName().trim().toUpperCase(), op.getTicker());
            groupByIsin.putIfAbsent(op.getAssetName().trim().toUpperCase(), op.getAeatGroup());
            if (op.getNotes() != null && op.getNotes().startsWith(NOTE_PREFIX)) {
                importedIds.add(transactionId(op.getNotes()));
            } else {
                manual.add(op);
            }
        }

        List<OperationForm> operations = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Map<String, Integer> ignored = new TreeMap<>();
        int duplicates = 0;

        for (int i = 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            int line = i + 1;
            if (row.stream().allMatch(String::isBlank)) continue;

            String category = get(row, col, "category");
            String type = get(row, col, "type");
            if (!isImportable(category, type)) {
                ignored.merge(label(category, type), 1, Integer::sum);
                continue;
            }

            String txId = get(row, col, "transaction_id");
            if (!txId.isEmpty() && !importedIds.add(txId)) {
                duplicates++;
                continue;
            }

            try {
                operations.add(toForm(row, col, type, txId, tickerByIsin, groupByIsin));
            } catch (IllegalArgumentException e) {
                errors.add("Línea " + line + ": " + e.getMessage());
            }
        }

        duplicates += manual.drop(operations);

        // Solo se piden valorar las que de verdad se van a dar de alta: si la entrega ya estaba
        // registrada, el usuario ya le puso precio en su día.
        List<String> pendingValuation = operations.stream()
                .filter(f -> f.getNotes() != null && f.getNotes().endsWith(NOTE_FREE_RECEIPT))
                .map(TradeRepublicCsvService::describe)
                .toList();

        return new TradeRepublicParseResult(operations, ignored, duplicates, pendingValuation, errors);
    }

    /**
     * Deja el importe en euros, que es la única divisa que la aplicación guarda.
     *
     * <p>Trade Republic liquida en la divisa de la cuenta, así que el importe ya viene en euros y
     * lo normal es que aquí no se convierta nada: la columna {@code original_currency} solo
     * documenta la pata extranjera de la operación, y en la práctica únicamente aparece en los
     * dividendos, que no se importan. Aun así, si algún día la liquidación llegara en otra divisa,
     * se convierte con el BCE a la fecha de la operación en vez de guardarla como si fueran euros,
     * que es un error que después no se detecta.
     *
     * <p>El BCE solo se consulta cuando hace falta de verdad. Consultarlo siempre añadiría un modo
     * de fallo por red a un importador que hoy no depende de ninguna.
     */
    private BigDecimal toEur(BigDecimal amount, String currency, LocalDate date) {
        String iso = currency == null ? "" : currency.trim().toUpperCase(Locale.ROOT);
        if (iso.isEmpty() || "EUR".equals(iso)) return amount;

        return fxRates.toEur(amount, iso, date).orElseThrow(() -> new IllegalArgumentException(
                "el importe viene en " + iso + " y no hay tipo de cambio del BCE para el "
                        + date + "."));
    }

    private OperationForm toForm(List<String> row, Map<String, Integer> col, String type,
                                 String txId, Map<String, String> tickerByIsin,
                                 Map<String, AeatGroup> groupByIsin) {
        String isin = isin(get(row, col, "symbol"));
        String name = get(row, col, "name");
        if (name.isEmpty()) throw new IllegalArgumentException("falta el nombre del valor.");

        BigDecimal shares = decimal(get(row, col, "shares"), "shares").abs();
        if (shares.signum() <= 0) throw new IllegalArgumentException("la cantidad debe ser mayor que 0.");

        boolean freeReceipt = FREE_RECEIPT.equalsIgnoreCase(type);
        BigDecimal fee = freeReceipt
                ? BigDecimal.ZERO
                : optionalDecimal(get(row, col, "fee"), "fee").abs();

        // total sigue el criterio de la aplicación: en una compra incluye la comisión (mayor valor
        // de adquisición) y en una venta la descuenta (menor valor de transmisión). La entrega
        // gratuita entra a cero porque el fichero no dice cuánto costó en su origen.
        BigDecimal total;
        OperationType opType;
        if (freeReceipt) {
            opType = OperationType.BUY;
            total = BigDecimal.ZERO;
        } else {
            opType = "BUY".equalsIgnoreCase(type) ? OperationType.BUY : OperationType.SELL;
            BigDecimal gross = grossAmount(row, col, shares);
            total = opType == OperationType.BUY ? gross.add(fee) : gross.subtract(fee);
            if (total.signum() <= 0) {
                throw new IllegalArgumentException(
                        "el importe resultante (" + total + ") no es mayor que 0.");
            }
            total = toEur(total, get(row, col, "currency"), date(get(row, col, "date")));
        }

        OperationForm f = new OperationForm();
        f.setDate(date(get(row, col, "date")));
        f.setType(opType);
        f.setTicker(tickerByIsin.computeIfAbsent(isin, k -> name.toUpperCase()));
        f.setAssetName(isin);
        f.setBroker(BROKER);
        f.setQuantity(shares);
        f.setTotal(total);
        f.setCommission(fee);
        f.setAeatGroup(groupByIsin.computeIfAbsent(isin, AeatGroup::forIsin));
        f.setNotes(note(txId, freeReceipt));
        return f;
    }

    /** "25/05/2026 · GRIFOLS · 138 títulos", para que el usuario sepa cuál tiene que valorar. */
    private static String describe(OperationForm f) {
        return DESCRIPTION_DATE.format(f.getDate()) + " · " + f.getTicker() + " · "
                + f.getQuantity().stripTrailingZeros().toPlainString() + " títulos";
    }

    private static String note(String txId, boolean freeReceipt) {
        if (txId.isEmpty()) return freeReceipt ? NOTE_FREE_RECEIPT : null;
        return NOTE_PREFIX + txId + (freeReceipt ? " " + NOTE_FREE_RECEIPT : "");
    }

    /**
     * Importe bruto de la operación, sin comisión. Se toma de {@code amount}; cuando viene vacío
     * —ocurre en las adjudicaciones de OPV— se reconstruye como cantidad × precio.
     */
    private BigDecimal grossAmount(List<String> row, Map<String, Integer> col, BigDecimal shares) {
        BigDecimal amount = optionalDecimal(get(row, col, "amount"), "amount");
        if (amount.signum() != 0) return amount.abs();

        BigDecimal price = optionalDecimal(get(row, col, "price"), "price");
        if (price.signum() == 0) {
            throw new IllegalArgumentException("no tiene ni importe ni precio, no se puede valorar.");
        }
        return shares.multiply(price);
    }

    /** Las cripto no traen ISIN; el resto de valores sí. */
    private static String isin(String symbol) {
        String s = symbol.trim().toUpperCase();
        if (s.isEmpty()) throw new IllegalArgumentException("falta el ISIN del valor.");
        return CRYPTO_ISIN.getOrDefault(s, s);
    }

    /** Movimientos que sí cambian la posición: compraventas y entregas gratuitas. */
    private static boolean isImportable(String category, String type) {
        if ("TRADING".equalsIgnoreCase(category)) {
            return "BUY".equalsIgnoreCase(type) || "SELL".equalsIgnoreCase(type);
        }
        return FREE_RECEIPT.equalsIgnoreCase(type);
    }

    /** El id ocupa hasta el primer espacio; lo que sigue son anotaciones nuestras. */
    private static String transactionId(String notes) {
        String rest = notes.substring(NOTE_PREFIX.length()).trim();
        int space = rest.indexOf(' ');
        return space < 0 ? rest : rest.substring(0, space);
    }

    private static String label(String category, String type) {
        String c = category.isEmpty() ? "?" : category;
        String t = type.isEmpty() ? "?" : type;
        return c + " / " + t;
    }

    private static Map<String, Integer> columnIndex(List<String> header) {
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            index.put(header.get(i).trim().toLowerCase(), i);
        }
        return index;
    }

    private static String get(List<String> row, Map<String, Integer> col, String name) {
        Integer i = col.get(name);
        return i == null || i >= row.size() ? "" : row.get(i).trim();
    }

    private static LocalDate date(String raw) {
        if (raw.isEmpty()) throw new IllegalArgumentException("falta la fecha.");
        try {
            return LocalDate.parse(raw);
        } catch (Exception e) {
            throw new IllegalArgumentException("fecha '" + raw + "' no válida (se espera yyyy-MM-dd).");
        }
    }

    private static BigDecimal decimal(String raw, String field) {
        if (raw.isEmpty()) throw new IllegalArgumentException("falta el campo '" + field + "'.");
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + raw + "' no es un número en '" + field + "'.");
        }
    }

    private static BigDecimal optionalDecimal(String raw, String field) {
        return raw.isEmpty() ? BigDecimal.ZERO : decimal(raw, field);
    }

}
