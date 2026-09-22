package com.raul.bolsa.service;

import com.raul.bolsa.domain.AeatGroup;
import com.raul.bolsa.domain.Operation;
import com.raul.bolsa.domain.OperationType;
import com.raul.bolsa.web.dto.IbkrParseResult;
import com.raul.bolsa.web.dto.OperationForm;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Traduce el <em>Activity Statement</em> de Interactive Brokers a operaciones de la cartera.
 *
 * <p>No es un CSV normal: es un montón de informes distintos pegados en un mismo fichero. Cada
 * línea empieza por el nombre de su sección y por si es cabecera o dato
 * ({@code Trades,Header,...} / {@code Trades,Data,...}), así que las columnas no son las mismas
 * de una línea a la siguiente y una misma sección puede reabrirse con otra cabecera cuando
 * cambia la categoría de activo. Por eso las columnas se resuelven por nombre y el mapa se
 * rehace cada vez que aparece una cabecera, en vez de fijar posiciones.
 *
 * <p>De todo el fichero se importan las compras y ventas de acciones y ETFs de la sección
 * {@code Trades}. Opciones, futuros, divisas y bonos se cuentan y se informan, pero no entran:
 * el FIFO por ticker no modela ni multiplicadores ni vencimientos, y meterlos como si fueran
 * acciones daría una cartera que cuadra por casualidad. Los dividendos, intereses y comisiones
 * sueltas quedan fuera porque son rendimientos del capital mobiliario, que esta aplicación no
 * lleva.
 *
 * <p>El ISIN sale de la sección {@code Financial Instrument Information}, que es la que traduce
 * el símbolo del broker. Si no estuviera, se busca el valor entre las operaciones que el usuario
 * ya tiene; y si tampoco, la importación se rechaza diciendo qué símbolos faltan. Inventar un
 * identificador a partir del símbolo sería peor que no importar: dos brokers llamarían distinto
 * al mismo valor y el FIFO se partiría en dos posiciones sin que nadie lo notase.
 *
 * <p>Las operaciones en divisa se pasan a euros con el tipo del BCE de la fecha de la operación,
 * igual que el extracto de MyInvestor. El cambio que aplicó el propio broker queda a un lado a
 * propósito: si el mismo valor puede comprarse en dos sitios, el criterio de coste tiene que ser
 * uno solo o el FIFO mezcla dos formas distintas de contar el mismo euro.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InteractiveBrokersCsvService {

    /** Broker asignado a todas las operaciones importadas de este fichero. */
    public static final String BROKER = "Interactive Brokers";

    /** Prefijo con el que se marca en las notas la operación de origen. */
    public static final String NOTE_PREFIX = "IBKR:";

    // ─── Secciones y tipos de fila ───────────────────────────────────────────

    private static final String SECTION_STATEMENT = "Statement";
    private static final String SECTION_TRADES = "Trades";
    private static final String SECTION_INSTRUMENTS = "Financial Instrument Information";
    private static final String ROW_HEADER = "Header";
    private static final String ROW_DATA = "Data";

    /** Primera columna con nombre: las dos anteriores son la sección y el tipo de fila. */
    private static final int FIRST_NAMED_COLUMN = 2;

    /**
     * Categorías que sí entran en la cartera. Los ETFs no tienen categoría propia en IB: van
     * dentro de {@code Stocks}, que es justo lo que interesa importar.
     */
    private static final Set<String> IMPORTABLE_CATEGORIES = Set.of("stocks");

    /**
     * Filas de detalle que no son movimientos. {@code ClosedLot} es el desglose del FIFO que
     * calcula el propio broker —su casación, no la nuestra— y sumarlo duplicaría cada venta.
     */
    private static final Set<String> DETAIL_DISCRIMINATORS = Set.of("closedlot", "subtotal", "total");

    /** Fila que agrupa las ejecuciones de una misma orden. */
    private static final String ORDER = "order";

    /** Una ejecución suelta: solo se usa cuando el fichero no trae la orden que la agrupa. */
    private static final String TRADE = "trade";

    /**
     * Las divisas se apuntan como si fueran valores, con el par por símbolo. La categoría ya las
     * distingue; esto es el cinturón por si un fichero la trajera vacía, porque importar un
     * cambio de euros a dólares como si fuera una compra de acciones descuadraría la cartera.
     */
    private static final Pattern FOREX_PAIR = Pattern.compile("[A-Z]{3}\\.[A-Z]{3}");

    private static final Pattern ISIN = Pattern.compile("[A-Z]{2}[A-Z0-9]{9}[0-9]");

    /** Un número a la americana: la coma solo aparece separando grupos de tres cifras. */
    private static final Pattern US_THOUSANDS =
            Pattern.compile("[+-]?\\d{1,3}(,\\d{3})+(\\.\\d+)?");

    private final EcbFxRateService fxRates;

    /**
     * ¿El fichero es un Activity Statement de Interactive Brokers?
     *
     * <p>Se reconoce por su propia estructura —una sección y un tipo de fila por línea—, que es
     * lo único estable: el nombre del fichero lo pone el usuario y la cabecera de las secciones
     * cambia con lo que cada cuenta tenga contratado.
     */
    public static boolean matches(List<List<String>> rows) {
        boolean statement = false;
        boolean trades = false;
        for (List<String> row : rows) {
            if (row.size() < 3 || !ROW_HEADER.equalsIgnoreCase(row.get(1).trim())) continue;
            String section = row.get(0).trim();
            if (SECTION_STATEMENT.equalsIgnoreCase(section)) statement = true;
            if (SECTION_TRADES.equalsIgnoreCase(section)
                    && names(row).containsAll(List.of("asset category", "symbol"))) {
                trades = true;
            }
        }
        return statement || trades;
    }

    /**
     * Convierte el fichero en operaciones listas para guardar.
     *
     * @param existing operaciones del usuario contra las que resolver ticker, ISIN, grupo AEAT y
     *                 duplicados; vacío cuando se va a reemplazar la cartera entera
     */
    public IbkrParseResult parse(List<List<String>> rows, List<Operation> existing) {
        Map<String, String> isinBySymbol = new LinkedHashMap<>();
        Map<String, String> nameBySymbol = new LinkedHashMap<>();
        List<Raw> raws = new ArrayList<>();
        readSections(rows, isinBySymbol, nameBySymbol, raws);

        if (raws.isEmpty()) {
            return IbkrParseResult.failed(List.of(
                    "El extracto no contiene ninguna operación en la sección 'Trades'."));
        }

        List<Raw> movements = withoutExecutionDetail(raws);

        Map<String, Integer> ignored = new TreeMap<>();
        List<Raw> importable = new ArrayList<>();
        for (Raw raw : movements) {
            if (isImportable(raw)) {
                importable.add(raw);
            } else {
                ignored.merge(raw.category().isEmpty() ? "?" : raw.category(), 1, Integer::sum);
            }
        }
        if (importable.isEmpty()) {
            return new IbkrParseResult(List.of(), ignored, 0, List.of());
        }

        // Lo que el usuario ya tiene manda sobre lo que diga el fichero: el mismo valor tiene que
        // conservar su ticker, o el FIFO lo trataría como dos posiciones distintas.
        Map<String, String> tickerByIsin = new HashMap<>();
        Map<String, AeatGroup> groupByIsin = new HashMap<>();
        Map<String, String> isinByTicker = new HashMap<>();
        Set<String> importedKeys = new HashSet<>();
        ManualOverlap manual = new ManualOverlap();
        for (Operation op : existing) {
            String isin = op.getAssetName().trim().toUpperCase(Locale.ROOT);
            tickerByIsin.putIfAbsent(isin, op.getTicker());
            groupByIsin.putIfAbsent(isin, op.getAeatGroup());
            isinByTicker.putIfAbsent(op.getTicker().trim().toUpperCase(Locale.ROOT), isin);
            if (op.getNotes() != null && op.getNotes().startsWith(NOTE_PREFIX)) {
                importedKeys.add(importKey(op.getNotes()));
            } else {
                manual.add(op);
            }
        }

        List<String> errors = new ArrayList<>(unresolvedSymbols(importable, isinBySymbol, isinByTicker));

        List<OperationForm> operations = new ArrayList<>();
        Map<String, Integer> seen = new HashMap<>();
        for (Raw raw : importable) {
            String isin = isinOf(raw.symbol(), isinBySymbol, isinByTicker);
            if (isin == null) continue;  // ya informado, símbolo a símbolo
            try {
                operations.add(toForm(raw, isin, nameBySymbol.get(raw.symbol()),
                        tickerByIsin, groupByIsin, seen));
            } catch (IllegalArgumentException e) {
                errors.add("Línea " + raw.line() + " (" + raw.symbol() + "): " + e.getMessage());
            }
        }

        if (!errors.isEmpty()) {
            return IbkrParseResult.failed(errors);
        }

        int duplicates = 0;
        for (var it = operations.iterator(); it.hasNext(); ) {
            if (importedKeys.contains(importKey(it.next().getNotes()))) {
                it.remove();
                duplicates++;
            }
        }
        duplicates += manual.drop(operations);

        return new IbkrParseResult(operations, ignored, duplicates, List.of());
    }

    // ─── Lectura del fichero ─────────────────────────────────────────────────

    /** Una fila de {@code Trades} tal cual viene, todavía sin interpretar sus números. */
    private record Raw(int line, String discriminator, String category, String currency,
                       String symbol, String dateTime, String quantity, String price,
                       String proceeds, String commission) {}

    /**
     * Recorre el fichero repartiendo cada línea en su sección.
     *
     * <p>Las columnas se rehacen con cada cabecera porque una misma sección puede reabrirse con
     * otras: en un extracto con opciones, {@code Trades} trae una cabecera para las acciones y
     * otra distinta para las opciones, con más columnas.
     */
    private static void readSections(List<List<String>> rows, Map<String, String> isinBySymbol,
                                     Map<String, String> nameBySymbol, List<Raw> raws) {
        Map<String, Integer> tradeCols = Map.of();
        Map<String, Integer> instrumentCols = Map.of();

        for (int i = 0; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            if (row.size() < FIRST_NAMED_COLUMN + 1) continue;

            String section = row.get(0).trim();
            String rowType = row.get(1).trim();

            if (SECTION_TRADES.equalsIgnoreCase(section)) {
                if (ROW_HEADER.equalsIgnoreCase(rowType)) {
                    tradeCols = columnIndex(row);
                } else if (ROW_DATA.equalsIgnoreCase(rowType) && !tradeCols.isEmpty()) {
                    raws.add(toRaw(row, tradeCols, i + 1));
                }
            } else if (SECTION_INSTRUMENTS.equalsIgnoreCase(section)) {
                if (ROW_HEADER.equalsIgnoreCase(rowType)) {
                    instrumentCols = columnIndex(row);
                } else if (ROW_DATA.equalsIgnoreCase(rowType) && !instrumentCols.isEmpty()) {
                    readInstrument(row, instrumentCols, isinBySymbol, nameBySymbol);
                }
            }
        }
    }

    private static Raw toRaw(List<String> row, Map<String, Integer> col, int line) {
        return new Raw(line,
                get(row, col, "datadiscriminator"),
                get(row, col, "asset category"),
                get(row, col, "currency"),
                get(row, col, "symbol").toUpperCase(Locale.ROOT),
                get(row, col, "date/time"),
                get(row, col, "quantity"),
                firstNonBlank(get(row, col, "t. price"), get(row, col, "trade price")),
                get(row, col, "proceeds"),
                firstNonBlank(get(row, col, "comm/fee"), get(row, col, "comm in eur"),
                        get(row, col, "commission")));
    }

    /**
     * El ISIN del valor. La columna se llama {@code Security ID} en unos extractos e {@code ISIN}
     * en otros, y en las cuentas con valores estadounidenses puede traer un CUSIP: se comprueba
     * la forma antes de quedárselo, porque un identificador que no es un ISIN rompería el enlace
     * con el resto de la cartera sin avisar.
     */
    private static void readInstrument(List<String> row, Map<String, Integer> col,
                                       Map<String, String> isinBySymbol,
                                       Map<String, String> nameBySymbol) {
        String symbol = get(row, col, "symbol").toUpperCase(Locale.ROOT);
        if (symbol.isEmpty()) return;

        String description = get(row, col, "description");
        if (!description.isEmpty()) nameBySymbol.putIfAbsent(symbol, description);

        String id = firstNonBlank(get(row, col, "isin"), get(row, col, "security id"))
                .toUpperCase(Locale.ROOT);
        if (ISIN.matcher(id).matches()) {
            isinBySymbol.putIfAbsent(symbol, id);
        }
    }

    // ─── Selección de las filas que son movimientos ──────────────────────────

    /**
     * Quita el detalle que no es un movimiento propio.
     *
     * <p>Con el desglose activado, el extracto trae cada orden y, debajo, las ejecuciones en que
     * casó en mercado. Las dos cosas son el mismo dinero, así que para cada valor se usa la orden
     * si está y las ejecuciones solo si no está. Se decide valor a valor y no para el fichero
     * entero porque el desglose puede venir activado solo en parte de las categorías.
     */
    private static List<Raw> withoutExecutionDetail(List<Raw> raws) {
        Set<String> withOrders = new HashSet<>();
        List<Raw> movements = new ArrayList<>();
        for (Raw raw : raws) {
            if (DETAIL_DISCRIMINATORS.contains(raw.discriminator().toLowerCase(Locale.ROOT))) continue;
            if (ORDER.equalsIgnoreCase(raw.discriminator())) withOrders.add(raw.symbol());
            movements.add(raw);
        }
        movements.removeIf(raw -> TRADE.equalsIgnoreCase(raw.discriminator())
                && withOrders.contains(raw.symbol()));
        return movements;
    }

    /** Acciones y ETFs, que es lo único que la cartera sabe modelar. */
    private static boolean isImportable(Raw raw) {
        if (FOREX_PAIR.matcher(raw.symbol()).matches()) return false;
        return IMPORTABLE_CATEGORIES.contains(raw.category().toLowerCase(Locale.ROOT));
    }

    /**
     * Un aviso por cada símbolo cuyo ISIN no se ha podido averiguar, no uno por línea: un valor
     * que falte aparece en decenas de operaciones y la lista de errores sería ilegible.
     */
    private static List<String> unresolvedSymbols(List<Raw> importable,
                                                  Map<String, String> isinBySymbol,
                                                  Map<String, String> isinByTicker) {
        Set<String> unresolved = new LinkedHashSet<>();
        for (Raw raw : importable) {
            if (isinOf(raw.symbol(), isinBySymbol, isinByTicker) == null) {
                unresolved.add(raw.symbol());
            }
        }
        return unresolved.stream()
                .map(symbol -> "No se ha podido averiguar el ISIN de " + symbol
                        + ": el extracto no lo trae en 'Financial Instrument Information' y no hay"
                        + " ninguna operación tuya con ese valor. Vuelve a descargarlo marcando"
                        + " esa sección, o da de alta una operación del valor a mano.")
                .toList();
    }

    private static String isinOf(String symbol, Map<String, String> isinBySymbol,
                                 Map<String, String> isinByTicker) {
        String fromFile = isinBySymbol.get(symbol);
        return fromFile != null ? fromFile : isinByTicker.get(symbol);
    }

    // ─── Conversión a operación ──────────────────────────────────────────────

    private OperationForm toForm(Raw raw, String isin, String description,
                                 Map<String, String> tickerByIsin,
                                 Map<String, AeatGroup> groupByIsin,
                                 Map<String, Integer> seen) {
        LocalDate date = date(raw.dateTime());
        BigDecimal signed = decimal(raw.quantity(), "Quantity");
        if (signed.signum() == 0) {
            throw new IllegalArgumentException("la cantidad es cero.");
        }
        OperationType type = signed.signum() > 0 ? OperationType.BUY : OperationType.SELL;
        BigDecimal quantity = signed.abs();
        BigDecimal commission = optionalDecimal(raw.commission(), "Comm/Fee").abs();

        // total sigue el criterio de la aplicación: en una compra la comisión suma al valor de
        // adquisición y en una venta se descuenta del de transmisión. Proceeds viene sin ella y
        // con el signo del dinero, así que basta el valor absoluto.
        BigDecimal gross = gross(raw, quantity);
        BigDecimal total = type == OperationType.BUY
                ? gross.add(commission)
                : gross.subtract(commission);
        if (total.signum() <= 0) {
            throw new IllegalArgumentException(
                    "el importe resultante (" + total.toPlainString() + ") no es mayor que 0.");
        }

        OperationForm f = new OperationForm();
        f.setDate(date);
        f.setType(type);
        f.setTicker(tickerByIsin.computeIfAbsent(isin, k -> raw.symbol()));
        f.setAssetName(isin);
        f.setBroker(BROKER);
        f.setQuantity(quantity);
        f.setTotal(toEur(total, raw.currency(), date));
        f.setCommission(toEur(commission, raw.currency(), date));
        f.setAeatGroup(groupByIsin.computeIfAbsent(isin, AeatGroup::forIsin));
        f.setNotes(NOTE_PREFIX + key(isin, raw, type, seen)
                + (description == null || description.isBlank() ? "" : " " + description));
        return f;
    }

    /**
     * Importe de la operación sin comisión. Sale de {@code Proceeds}, y si viniera vacío se
     * reconstruye como cantidad × precio, que es lo mismo salvo redondeo.
     */
    private static BigDecimal gross(Raw raw, BigDecimal quantity) {
        BigDecimal proceeds = optionalDecimal(raw.proceeds(), "Proceeds").abs();
        if (proceeds.signum() > 0) return proceeds;

        BigDecimal price = optionalDecimal(raw.price(), "T. Price");
        if (price.signum() <= 0) {
            throw new IllegalArgumentException("no tiene ni importe ni precio, no se puede valorar.");
        }
        return quantity.multiply(price);
    }

    /**
     * Deja el importe en euros. Si el BCE no tiene tipo para ese día se rechaza la fila en vez de
     * guardarla como si ya fueran euros: un valor de adquisición mal convertido no se detecta
     * después, y sale a la luz años más tarde en la declaración.
     */
    private BigDecimal toEur(BigDecimal amount, String currency, LocalDate date) {
        String iso = currency == null ? "" : currency.trim().toUpperCase(Locale.ROOT);
        if (iso.isEmpty()) {
            throw new IllegalArgumentException("no dice en qué divisa está el importe.");
        }
        return fxRates.toEur(amount, iso, date).orElseThrow(() -> new IllegalArgumentException(
                "el importe viene en " + iso + " y no hay tipo de cambio del BCE para el "
                        + date + "; reintenta cuando haya conexión."));
    }

    /**
     * Identidad de la operación, con la que se reconoce lo ya importado al volver a subir un
     * extracto que solapa con el anterior.
     *
     * <p>El Activity Statement no da ningún número de operación, así que la identidad se compone
     * con lo que sí identifica el hecho: el valor, el momento exacto, el sentido y la cantidad.
     * El sufijo cubre el caso de dos ejecuciones idénticas en el mismo segundo, y es estable
     * porque el extracto nunca parte un día por la mitad: el día entero viene siempre en el mismo
     * fichero, y por tanto en el mismo orden.
     */
    private static String key(String isin, Raw raw, OperationType type, Map<String, Integer> seen) {
        String base = isin + ":" + stamp(raw.dateTime()) + ":" + type.name() + ":"
                + decimal(raw.quantity(), "Quantity").abs().stripTrailingZeros().toPlainString();
        int n = seen.merge(base, 1, Integer::sum);
        return n == 1 ? base : base + "#" + n;
    }

    /** La clave ocupa hasta el primer espacio; lo que sigue son anotaciones nuestras. */
    private static String importKey(String notes) {
        String rest = notes.substring(NOTE_PREFIX.length()).trim();
        int space = rest.indexOf(' ');
        return space < 0 ? rest : rest.substring(0, space);
    }

    // ─── Lectura de campos ───────────────────────────────────────────────────

    private static Map<String, Integer> columnIndex(List<String> header) {
        Map<String, Integer> index = new HashMap<>();
        for (int i = FIRST_NAMED_COLUMN; i < header.size(); i++) {
            index.putIfAbsent(header.get(i).trim().toLowerCase(Locale.ROOT), i);
        }
        return index;
    }

    private static List<String> names(List<String> header) {
        return List.copyOf(columnIndex(header).keySet());
    }

    private static String get(List<String> row, Map<String, Integer> col, String name) {
        Integer i = col.get(name);
        return i == null || i >= row.size() ? "" : row.get(i).trim();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return "";
    }

    /** {@code "2025-07-28, 09:45:12"} → {@code 2025-07-28}. La hora solo sirve de identidad. */
    private static LocalDate date(String raw) {
        String stamp = stamp(raw);
        if (stamp.length() < 10) throw new IllegalArgumentException("falta la fecha.");
        try {
            return LocalDate.parse(stamp.substring(0, 10));
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "fecha '" + raw.trim() + "' no válida (se espera yyyy-MM-dd).");
        }
    }

    /** {@code "2025-07-28, 09:45:12"} → {@code "2025-07-28T09:45:12"}. */
    private static String stamp(String raw) {
        return raw == null ? "" : raw.trim().replaceFirst(",\\s*", "T").replace(" ", "T");
    }

    /**
     * Interactive Brokers escribe los números a la americana: punto decimal y coma de millares.
     *
     * <p>Antes de quitar las comas se comprueba que de verdad separan millares, o sea que cada
     * una va seguida de exactamente tres cifras. No es una manía: si un extracto llegara con los
     * números a la europea, quitarle la coma a {@code 1.234,56} daría {@code 1.23456} y el
     * importe entraría en la cartera dividido por cien sin que saltara nada. Un valor de
     * adquisición mal leído no se nota al importar; se nota años después, en la declaración.
     */
    private static BigDecimal decimal(String raw, String field) {
        String v = raw == null ? "" : raw.trim();
        if (v.isEmpty()) throw new IllegalArgumentException("falta el campo '" + field + "'.");
        if (v.indexOf(',') >= 0) {
            if (!US_THOUSANDS.matcher(v).matches()) {
                throw new IllegalArgumentException("'" + v + "' no es un número en '" + field
                        + "': Interactive Brokers usa el punto como separador decimal y la coma"
                        + " solo para los millares.");
            }
            v = v.replace(",", "");
        }
        try {
            return new BigDecimal(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + raw.trim() + "' no es un número en '" + field + "'.");
        }
    }

    private static BigDecimal optionalDecimal(String raw, String field) {
        return raw == null || raw.isBlank() ? BigDecimal.ZERO : decimal(raw, field);
    }
}
