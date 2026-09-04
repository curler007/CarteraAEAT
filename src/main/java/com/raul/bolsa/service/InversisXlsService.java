package com.raul.bolsa.service;

import com.raul.bolsa.domain.AeatGroup;
import com.raul.bolsa.domain.Operation;
import com.raul.bolsa.domain.OperationType;
import com.raul.bolsa.web.dto.InversisParseResult;
import com.raul.bolsa.web.dto.OperationForm;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Traduce el extracto de movimientos de Inversis —el depositario que hay detrás de las carteras
 * automatizadas de MyInvestor— a operaciones de la cartera.
 *
 * <p>El fichero se descarga con extensión {@code .xls} pero no es un Excel: es una tabla HTML en
 * ISO-8859-1, que es como la sirve el gestor. Trae once columnas por movimiento, y las que
 * importan son el ISIN, los títulos, la divisa y el importe neto.
 *
 * <p>Un rebalanceo se ejecuta como decenas de órdenes sueltas del mismo fondo y el mismo día, así
 * que las filas se agrupan por fecha, ISIN y tipo. Sin eso, un solo traspaso metería más de cien
 * operaciones en el listado y otros tantos lotes en la base de datos, cuando lo que ha pasado es
 * un único movimiento por fondo.
 *
 * <p>De los seis tipos de movimiento que usa Inversis, solo el {@code REEMBOLSO} tributa. Los
 * traspasos y los cambios de clase son neutros (LIRPF Art. 94): no generan ganancia, pero
 * arrastran al fondo de destino el coste y la antigüedad del de origen, y por eso se importan
 * como las dos patas de un traspaso en vez de como compraventas.
 *
 * <p>Los fondos denominados en divisa traen precio e importe en esa divisa, no en euros, así que
 * se convierten con el tipo del BCE de la fecha de la operación.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InversisXlsService {

    /** Broker asignado a todas las operaciones importadas de este fichero. */
    public static final String BROKER = "MyInvestor";

    /** Prefijo con el que se marca en las notas el movimiento de origen. */
    public static final String NOTE_PREFIX = "INV:";

    /** Columnas de la tabla, en el orden en que las sirve Inversis. */
    private static final int COL_DATE = 0;
    private static final int COL_ORDER = 2;
    private static final int COL_TYPE = 4;
    private static final int COL_ISIN = 5;
    private static final int COL_NAME = 6;
    private static final int COL_QTY = 7;
    private static final int COL_CURRENCY = 8;
    private static final int COL_AMOUNT = 10;
    private static final int COLUMNS = 11;

    /**
     * Tipos de movimiento de Inversis. El traspaso y el cambio de clase de participaciones
     * ("switch") se tratan igual: los dos mueven dinero entre fondos sin tributar.
     */
    private static final Map<String, OperationType> TYPES = Map.of(
            "SUSCRIPCION", OperationType.BUY,
            "REEMBOLSO", OperationType.SELL,
            "SUSCR.POR TRASPASO I", OperationType.TRASPASO_IN,
            "REEMB.POR TRASPASO I", OperationType.TRASPASO_OUT,
            "ALTA IIC SWITCH", OperationType.TRASPASO_IN,
            "BAJA IIC SWITCH", OperationType.TRASPASO_OUT);

    /**
     * Días de margen para dar por cerrado un traspaso. Las órdenes de un mismo rebalanceo se
     * liquidan en días sucesivos —primero los reembolsos, después las suscripciones, según lo que
     * tarde cada gestora—, pero entre dos rebalanceos pasan semanas.
     */
    private static final int TRANSFER_GAP_DAYS = 5;

    private static final Pattern ROW = Pattern.compile("<tr[^>]*>(.*?)</tr>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern CELL = Pattern.compile("<t[dh][^>]*>(.*?)</t[dh]>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private final EcbFxRateService fxRates;

    /**
     * ¿El fichero es un extracto de Inversis? Se reconoce por sus propias cabeceras, que son las
     * únicas señas fiables: la extensión miente y el HTML no lleva ninguna marca del emisor.
     */
    public static boolean matches(byte[] content) {
        String head = new String(content, 0, Math.min(content.length, 4096),
                StandardCharsets.ISO_8859_1).toUpperCase();
        return head.contains("<TABLE") && head.contains("ISIN")
                && head.contains("IMPORTE NETO") && head.contains("LIQUIDACI");
    }

    /**
     * Convierte el fichero en operaciones listas para guardar.
     *
     * @param existing operaciones del usuario contra las que resolver el grupo AEAT y los
     *                 duplicados; vacío cuando se va a reemplazar la cartera entera
     */
    public InversisParseResult parse(byte[] content, List<Operation> existing) {
        List<String> errors = new ArrayList<>();
        Map<String, Integer> ignored = new TreeMap<>();
        List<Movement> movements = new ArrayList<>();

        List<List<String>> rows = readTable(content);
        if (rows.isEmpty()) {
            return new InversisParseResult(List.of(), Map.of(), 0, List.of(),
                    List.of("El fichero no contiene ninguna tabla de movimientos."));
        }

        for (int i = 0; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            int line = i + 1;
            String rawType = row.get(COL_TYPE).toUpperCase();
            OperationType type = TYPES.get(rawType);
            if (type == null) {
                ignored.merge(row.get(COL_TYPE), 1, Integer::sum);
                continue;
            }
            try {
                movements.add(toMovement(row, type));
            } catch (IllegalArgumentException e) {
                errors.add("Movimiento " + line + " (" + row.get(COL_ORDER).trim() + "): "
                        + e.getMessage());
            }
        }

        if (!errors.isEmpty()) {
            return new InversisParseResult(List.of(), ignored, 0, List.of(), errors);
        }
        if (movements.isEmpty()) {
            return new InversisParseResult(List.of(), ignored, 0, List.of(),
                    List.of("El fichero no contiene ningún movimiento de fondos reconocible."));
        }

        Map<String, AeatGroup> groupByIsin = new HashMap<>();
        Map<String, String> tickerByIsin = new HashMap<>();
        Set<String> alreadyImported = new HashSet<>();
        for (Operation op : existing) {
            String isin = op.getAssetName().trim().toUpperCase();
            groupByIsin.putIfAbsent(isin, op.getAeatGroup());
            // Si el usuario ya tenía ese fondo, se respeta el nombre con el que lo llamaba: dos
            // tickers para el mismo ISIN partirían el FIFO en dos posiciones independientes.
            tickerByIsin.putIfAbsent(isin, op.getTicker());
            if (op.getNotes() != null && op.getNotes().startsWith(NOTE_PREFIX)) {
                alreadyImported.add(importKey(op.getNotes()));
            }
        }
        currentNames(movements).forEach(tickerByIsin::putIfAbsent);

        List<OperationForm> forms = new ArrayList<>();
        int duplicates = 0;
        for (Aggregate agg : aggregate(movements)) {
            if (alreadyImported.contains(agg.key())) {
                duplicates++;
                continue;
            }
            forms.add(agg.toForm(tickerByIsin, groupByIsin));
        }

        return new InversisParseResult(forms, ignored, duplicates,
                transferWarnings(forms), List.of());
    }

    // ─── Lectura del fichero ─────────────────────────────────────────────────

    /** Filas de datos de la tabla: las que tienen las once columnas y empiezan por una fecha. */
    private static List<List<String>> readTable(byte[] content) {
        String html = new String(content, StandardCharsets.ISO_8859_1);
        List<List<String>> rows = new ArrayList<>();

        Matcher rowMatcher = ROW.matcher(html);
        while (rowMatcher.find()) {
            List<String> cells = new ArrayList<>();
            Matcher cellMatcher = CELL.matcher(rowMatcher.group(1));
            while (cellMatcher.find()) {
                cells.add(unescape(TAG.matcher(cellMatcher.group(1)).replaceAll("")));
            }
            if (cells.size() == COLUMNS && ISO_DATE.matcher(cells.get(COL_DATE)).lookingAt()) {
                rows.add(cells);
            }
        }
        return rows;
    }

    private static String unescape(String raw) {
        return raw.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&nbsp;", " ")
                .replace(' ', ' ')
                .trim();
    }

    /** Una fila del extracto, ya con el importe en euros. */
    private record Movement(LocalDate date, String isin, String name, OperationType type,
                            BigDecimal quantity, BigDecimal amount) {}

    private Movement toMovement(List<String> row, OperationType type) {
        LocalDate date = date(row.get(COL_DATE));
        String isin = required(row.get(COL_ISIN), "ISIN").toUpperCase();
        BigDecimal quantity = positive(row.get(COL_QTY), "Títulos");
        BigDecimal amount = positive(row.get(COL_AMOUNT), "Importe neto");
        String currency = required(row.get(COL_CURRENCY), "Divisa");

        BigDecimal eur = fxRates.toEur(amount, currency, date).orElseThrow(() ->
                new IllegalArgumentException("el importe viene en " + currency
                        + " y no hay tipo de cambio del BCE para el " + DATE.format(date)
                        + "; reintenta cuando haya conexión."));

        String name = row.get(COL_NAME).trim();
        return new Movement(date, isin, name.isEmpty() ? isin : name, type, quantity,
                eur.setScale(6, RoundingMode.HALF_UP));
    }

    /**
     * Nombre vigente de cada fondo: el del movimiento más reciente. El extracto arrastra el
     * nombre que el fondo tenía en cada momento, y las gestoras los cambian —o el gestor cambia
     * de criterio al etiquetarlos—, así que quedarse con el último es lo que casa con lo que el
     * usuario ve hoy en la aplicación del banco.
     *
     * <p>Sobre todo, tiene que ser <em>uno solo</em> por ISIN: si el mismo fondo entrase con dos
     * nombres, quedarían dos posiciones independientes y cada una haría su propio FIFO.
     */
    private static Map<String, String> currentNames(List<Movement> movements) {
        Map<String, Movement> latest = new LinkedHashMap<>();
        for (Movement m : movements) {
            latest.merge(m.isin(), m,
                    (a, b) -> b.date().isBefore(a.date()) ? a : b);
        }
        Map<String, String> out = new LinkedHashMap<>();
        latest.forEach((isin, m) -> out.put(isin, m.name()));
        return out;
    }

    // ─── Agrupación ──────────────────────────────────────────────────────────

    /**
     * Todas las órdenes de un mismo fondo, día y tipo, sumadas en una sola operación.
     *
     * @param transferId traspaso al que pertenece, null si no es una pata de traspaso
     */
    private record Aggregate(LocalDate date, String isin, String name, OperationType type,
                             BigDecimal quantity, BigDecimal amount, int orders,
                             String transferId) {

        /**
         * Identificador estable del movimiento agregado. No usa los números de orden porque
         * Inversis reparte una misma operación en un número variable de ellos; la fecha, el fondo
         * y el tipo sí identifican el hecho económico, que es lo que se importa.
         */
        String key() {
            return DATE.format(date) + ":" + isin + ":" + type.name();
        }

        OperationForm toForm(Map<String, String> tickerByIsin, Map<String, AeatGroup> groupByIsin) {
            OperationForm f = new OperationForm();
            f.setDate(date);
            f.setType(type);
            // El ticker es el nombre del fondo, que es como lo reconoce el usuario; el ISIN va a
            // assetName, que es de donde sale la cotización.
            f.setTicker(tickerByIsin.getOrDefault(isin, isin));
            f.setAssetName(isin);
            f.setBroker(BROKER);
            f.setQuantity(quantity);
            f.setTotal(amount);
            f.setCommission(BigDecimal.ZERO);
            f.setAeatGroup(groupByIsin.getOrDefault(isin, AeatGroup.forIsin(isin)));
            f.setTransferId(transferId);
            f.setNotes(NOTE_PREFIX + key() + " " + name
                    + " (" + orders + (orders == 1 ? " orden)" : " órdenes)"));
            return f;
        }
    }

    /** Agrupa por fecha, fondo y tipo, y reparte las patas de traspaso entre sus eventos. */
    private static List<Aggregate> aggregate(List<Movement> movements) {
        Map<LocalDate, String> transferIds = transferEvents(movements);

        record Key(LocalDate date, String isin, OperationType type) {}
        Map<Key, List<Movement>> byKey = new LinkedHashMap<>();
        for (Movement m : movements) {
            byKey.computeIfAbsent(new Key(m.date(), m.isin(), m.type()), k -> new ArrayList<>())
                    .add(m);
        }

        List<Aggregate> out = new ArrayList<>();
        byKey.forEach((key, group) -> {
            BigDecimal quantity = group.stream()
                    .map(Movement::quantity).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal amount = group.stream()
                    .map(Movement::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            out.add(new Aggregate(key.date(), key.isin(), group.get(0).name(), key.type(),
                    quantity, amount, group.size(),
                    key.type().isTransfer() ? transferIds.get(key.date()) : null));
        });
        out.sort((a, b) -> a.date().compareTo(b.date()));
        return out;
    }

    /**
     * Reparte las patas de traspaso en eventos y devuelve, para cada fecha, el identificador del
     * evento al que pertenece. Un rebalanceo se agrupa por cercanía en el tiempo porque el
     * extracto no da ningún identificador que ate el reembolso de un fondo con la suscripción del
     * otro: lo único que los relaciona es haberse ejecutado seguidos.
     */
    private static Map<LocalDate, String> transferEvents(List<Movement> movements) {
        List<LocalDate> dates = movements.stream()
                .filter(m -> m.type().isTransfer())
                .map(Movement::date)
                .distinct()
                .sorted()
                .toList();

        Map<LocalDate, String> ids = new HashMap<>();
        LocalDate eventStart = null;
        LocalDate previous = null;
        for (LocalDate date : dates) {
            if (previous == null || previous.plusDays(TRANSFER_GAP_DAYS).isBefore(date)) {
                eventStart = date;
            }
            ids.put(date, NOTE_PREFIX + DATE.format(eventStart));
            previous = date;
        }
        return ids;
    }

    /**
     * Descuadre a partir del cual se avisa de un traspaso. Lo que sale de unos fondos y lo que
     * entra en otros es el mismo dinero, así que solo deberían separarlos el redondeo y, en los
     * fondos en divisa, la diferencia entre el cambio del BCE y el que aplicó el banco.
     */
    private static final BigDecimal TRANSFER_TOLERANCE = new BigDecimal("0.01");

    /**
     * Traspasos que no acaban de cuadrar, para poder avisar. Son dos avisos distintos.
     *
     * <p>Que falte una de las dos patas pasa al importar un fichero que empieza más tarde que la
     * cartera: sin el reembolso de origen no hay coste que heredar y la entrada se da de alta por
     * el valor con el que entró, que no tiene por qué ser el que costó.
     *
     * <p>Que las dos patas estén pero no sumen lo mismo apunta a otra cosa: el extracto no trae
     * ningún identificador que las ate, así que se agrupan por cercanía en el tiempo, y si dos
     * traspasos sin relación cayeran demasiado juntos se mezclarían en uno. El importe es la
     * única señal que queda para detectarlo.
     */
    private static List<String> transferWarnings(List<OperationForm> forms) {
        Map<String, List<OperationForm>> events = new LinkedHashMap<>();
        for (OperationForm f : forms) {
            if (f.getTransferId() != null) {
                events.computeIfAbsent(f.getTransferId(), k -> new ArrayList<>()).add(f);
            }
        }

        List<String> warnings = new ArrayList<>();
        events.forEach((id, event) -> {
            String when = id.substring(NOTE_PREFIX.length());
            BigDecimal out = sumOf(event, OperationType.TRASPASO_OUT);
            BigDecimal in = sumOf(event, OperationType.TRASPASO_IN);

            if (out.signum() == 0) {
                warnings.add("el traspaso del " + when + " no trae el fondo de origen");
            } else if (in.signum() == 0) {
                warnings.add("el traspaso del " + when + " no trae el fondo de destino");
            } else {
                BigDecimal gap = in.subtract(out).abs();
                if (gap.compareTo(out.multiply(TRANSFER_TOLERANCE)) > 0) {
                    warnings.add("en el traspaso del " + when + " no cuadra lo que sale ("
                            + money(out) + " €) con lo que entra (" + money(in) + " €)");
                }
            }
        });
        return warnings;
    }

    private static BigDecimal sumOf(List<OperationForm> event, OperationType type) {
        return event.stream().filter(f -> f.getType() == type)
                .map(OperationForm::getTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static String money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** La clave ocupa hasta el primer espacio; lo que sigue son anotaciones nuestras. */
    private static String importKey(String notes) {
        String rest = notes.substring(NOTE_PREFIX.length()).trim();
        int space = rest.indexOf(' ');
        return space < 0 ? rest : rest.substring(0, space);
    }

    // ─── Validación de campos ────────────────────────────────────────────────

    private static String required(String raw, String field) {
        String v = raw == null ? "" : raw.trim();
        if (v.isEmpty()) throw new IllegalArgumentException("falta " + field + ".");
        return v;
    }

    private static LocalDate date(String raw) {
        String v = required(raw, "la fecha");
        try {
            return LocalDate.parse(v.substring(0, 10), DATE);
        } catch (Exception e) {
            throw new IllegalArgumentException("la fecha '" + v + "' no es válida.");
        }
    }

    /**
     * Inversis escribe los números con punto decimal y sin separador de miles. Se admite también
     * la notación española por si el gestor cambia de criterio: con coma presente, el punto solo
     * puede ser separador de miles.
     */
    private static BigDecimal positive(String raw, String field) {
        String v = required(raw, field).replace(" ", "");
        if (v.indexOf(',') >= 0) v = v.replace(".", "").replace(',', '.');
        try {
            BigDecimal value = new BigDecimal(v);
            if (value.signum() <= 0) {
                throw new IllegalArgumentException(field + " debe ser mayor que 0.");
            }
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(field + ": '" + raw.trim() + "' no es un número.");
        }
    }
}
