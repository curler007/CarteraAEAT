package com.raul.bolsa.service;

import com.raul.bolsa.domain.AeatGroup;
import com.raul.bolsa.domain.Operation;
import com.raul.bolsa.domain.OperationType;
import com.raul.bolsa.domain.Split;
import com.raul.bolsa.repository.FifoLotRepository;
import com.raul.bolsa.repository.OperationRepository;
import com.raul.bolsa.repository.SaleRecordRepository;
import com.raul.bolsa.repository.SplitRepository;
import com.raul.bolsa.web.dto.CsvImportResult;
import com.raul.bolsa.web.dto.ImportConflict;
import com.raul.bolsa.web.dto.InversisParseResult;
import com.raul.bolsa.web.dto.ImportMode;
import com.raul.bolsa.web.dto.OperationForm;
import com.raul.bolsa.web.dto.SplitForm;
import com.raul.bolsa.web.dto.TradeRepublicParseResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Exporta e importa la cartera de un usuario en un único CSV.
 *
 * <p>El fichero incluye también los splits (filas con Tipo=SPLIT, usando la columna Cantidad
 * para el ratio): sin ellos el FIFO de un valor que haya sufrido un split se reconstruiría mal,
 * y el error sería silencioso.
 *
 * <p>Formato: separador {@code ;}, UTF-8 con BOM y fechas {@code dd/MM/yyyy}, igual que la
 * exportación AEAT ya existente. Los decimales se escriben con coma (Excel en español) pero al
 * importar se aceptan indistintamente coma y punto.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OperationCsvService {

    public static final String HEADER =
            "Fecha;Tipo;Ticker;ISIN;Broker;Cantidad;Total;Comision;Grupo AEAT;Notas;Traspaso;Uid";

    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final DateTimeFormatter OUT_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final char SEP = ';';

    /**
     * Las columnas {@code Traspaso} y {@code Uid} se añadieron después, así que un fichero
     * exportado antes trae diez u once columnas y sigue siendo válido: sin traspasos no hay nada
     * que emparejar, y sin uid cada fila se toma por una operación nueva, que es justo lo que
     * pasaba antes de que existiera la columna.
     */
    private static final int COLUMNS_MIN = 10;
    private static final int COLUMNS = 12;
    private static final int COL_TRANSFER = 10;
    private static final int COL_UID = 11;

    /** Tipo reservado para las filas de split; el resto son valores de OperationType. */
    private static final String SPLIT = "SPLIT";

    public static final String FORMAT_OWN = "formato propio";
    public static final String FORMAT_TRADE_REPUBLIC = "Trade Republic";
    public static final String FORMAT_INVERSIS = "MyInvestor";

    private final OperationRepository operationRepo;
    private final SplitRepository splitRepo;
    private final FifoLotRepository fifoLotRepo;
    private final SaleRecordRepository saleRecordRepo;
    private final OperationService operationService;
    private final SplitService splitService;
    private final TradeRepublicCsvService tradeRepublicService;
    private final InversisXlsService inversisService;
    private final FifoService fifoService;

    // ─── Exportación ─────────────────────────────────────────────────────────

    /** Todas las operaciones y splits del usuario, en orden cronológico. */
    public byte[] export(Long userId) {
        List<String[]> rows = new ArrayList<>();

        for (Operation op : operationRepo.findByUserId(userId)) {
            rows.add(new String[]{
                    OUT_DATE.format(op.getDate()),
                    op.getType().name(),
                    op.getTicker(),
                    op.getAssetName(),
                    op.getBroker(),
                    num(op.getQuantity()),
                    op.getType() == OperationType.CANJE ? "" : num(op.getTotal()),
                    op.getType() == OperationType.CANJE ? "" : num(op.getCommission()),
                    op.getAeatGroup().name(),
                    op.getNotes(),
                    op.getTransferId(),
                    op.getUid()
            });
        }
        for (Split s : splitRepo.findByUserId(userId)) {
            rows.add(new String[]{
                    OUT_DATE.format(s.getDate()), SPLIT, s.getTicker(),
                    "", "", num(s.getRatio()), "", "", "", "", "", ""
            });
        }

        // Cronológico y, dentro del mismo día, splits primero: es el orden en que
        // recalculateFifo() reproduce los hechos.
        rows.sort(Comparator
                .<String[], LocalDate>comparing(r -> LocalDate.parse(r[0], OUT_DATE))
                .thenComparing(r -> SPLIT.equals(r[1]) ? 0 : 1));

        StringBuilder sb = new StringBuilder(HEADER).append('\n');
        for (String[] row : rows) {
            for (int i = 0; i < row.length; i++) {
                if (i > 0) sb.append(SEP);
                sb.append(escape(row[i]));
            }
            sb.append('\n');
        }

        byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[BOM.length + body.length];
        System.arraycopy(BOM, 0, out, 0, BOM.length);
        System.arraycopy(body, 0, out, BOM.length, body.length);
        return out;
    }

    // ─── Importación ─────────────────────────────────────────────────────────

    /**
     * Valida el fichero entero antes de escribir nada: si alguna fila es inválida no se
     * importa ninguna y se devuelven todos los errores con su número de línea.
     */
    @Transactional
    public CsvImportResult importCsv(Long userId, byte[] content, ImportMode mode) {
        return importCsv(userId, content, mode, null);
    }

    /**
     * Igual, pero con las decisiones ya tomadas sobre las operaciones que el fichero trae
     * cambiadas: las de {@code uidsToUpdate} se actualizan y el resto se dejan como están.
     *
     * @param uidsToUpdate null mientras nadie haya decidido nada, y entonces cualquier operación
     *                     cambiada detiene la importación en vez de escribirse
     */
    @Transactional
    public CsvImportResult importCsv(Long userId, byte[] content, ImportMode mode,
                                     Set<String> uidsToUpdate) {
        // Antes de decodificar: el extracto de MyInvestor no es texto UTF-8 ni es un CSV.
        if (InversisXlsService.matches(content)) {
            return importInversis(userId, content, mode);
        }

        String text = stripBom(new String(content, StandardCharsets.UTF_8));

        List<List<String>> tradeRepublicRows = parse(text, TradeRepublicCsvService.separator());
        if (!tradeRepublicRows.isEmpty() && TradeRepublicCsvService.matches(tradeRepublicRows.get(0))) {
            return importTradeRepublic(userId, tradeRepublicRows, mode);
        }

        List<List<String>> rows = parse(text);

        if (rows.isEmpty()) {
            return CsvImportResult.failed(List.of("El fichero está vacío."));
        }

        List<String> errors = new ArrayList<>();
        List<OperationForm> operations = new ArrayList<>();
        List<SplitForm> splits = new ArrayList<>();

        int firstRow = looksLikeHeader(rows.get(0)) ? 1 : 0;
        if (firstRow == 0) {
            errors.add("Línea 1: falta la fila de cabecera. Debe ser exactamente: " + HEADER);
        }

        for (int i = firstRow; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            int line = i + 1;
            if (row.stream().allMatch(String::isBlank)) continue;  // línea en blanco
            if (row.size() < COLUMNS_MIN || row.size() > COLUMNS) {
                errors.add("Línea " + line + ": se esperaban " + COLUMNS
                        + " columnas separadas por ';' y hay " + row.size() + ".");
                continue;
            }
            try {
                parseRow(row, operations, splits);
            } catch (IllegalArgumentException e) {
                errors.add("Línea " + line + ": " + e.getMessage());
            }
        }

        if (operations.isEmpty() && splits.isEmpty() && errors.isEmpty()) {
            errors.add("El fichero no contiene ninguna operación.");
        }
        if (!errors.isEmpty()) {
            return CsvImportResult.failed(errors);
        }

        // En REPLACE no hay nada con lo que chocar: la cartera se vacía y se reconstruye entera.
        Incoming incoming = mode == ImportMode.REPLACE
                ? new Incoming(operations, Map.of(), List.of(), 0)
                : classify(userId, operations, uidsToUpdate);

        if (!incoming.conflicts().isEmpty()) {
            return CsvImportResult.undecided(FORMAT_OWN, incoming.conflicts());
        }

        if (mode == ImportMode.REPLACE) {
            deleteEverythingOf(userId);
        } else {
            splits = newSplits(userId, splits);
        }

        // Lo que cambia va antes que lo que nace: así el FIFO se reconstruye una sola vez sobre
        // los datos definitivos en lugar de dos, con los viejos por medio.
        incoming.updates().forEach((id, f) -> operationService.update(userId, id, f));

        operations = incoming.nuevas();

        // Las operaciones primero y los splits después, en orden cronológico: es el mismo
        // camino que valida ReplayConsistencyTest.
        operations.sort(Comparator.comparing(OperationForm::getDate));
        // Con traspasos de por medio cada alta recalcularía la cartera entera, así que el fichero
        // se carga de una vez y se recalcula al final. Sin ellos se conserva el alta operación a
        // operación, que resuelve el FIFO por valor y no hace falta tocar.
        if (operations.stream().anyMatch(f -> f.getType().isTransfer())) {
            operations.forEach(f -> operationService.saveDeferred(userId, f));
            splits.sort(Comparator.comparing(SplitForm::getDate));
            splits.forEach(f -> splitService.saveWithoutRecalc(userId, f));
            fifoService.recalculateAll(userId);
        } else {
            operations.forEach(f -> operationService.save(userId, f));
            splits.sort(Comparator.comparing(SplitForm::getDate));
            splits.forEach(f -> splitService.save(userId, f));
        }

        log.info("Importadas {} operaciones ({} actualizadas, {} ya estaban) y {} splits "
                        + "para el usuario {} (modo {})",
                operations.size(), incoming.updates().size(), incoming.duplicates(),
                splits.size(), userId, mode);
        return new CsvImportResult(FORMAT_OWN,
                operations.size() + incoming.updates().size(), splits.size(),
                Map.of(), incoming.duplicates(), List.of(), List.of(), List.of(), List.of());
    }

    // ─── Reconocimiento de lo ya importado ───────────────────────────────────

    /** Reparto de las filas del fichero contra lo que el usuario ya tiene guardado. */
    private record Incoming(List<OperationForm> nuevas,
                            Map<Long, OperationForm> updates,
                            List<ImportConflict> conflicts,
                            int duplicates) {}

    /**
     * Separa las filas en las que son nuevas, las que ya estaban igual y las que ya estaban pero
     * han cambiado.
     *
     * <p>El reconocimiento va por {@code uid} y solo por él. Una clave deducida del contenido
     * —fecha, valor, tipo e importe— parece equivalente y no lo es: dos ejecuciones idénticas
     * del mismo valor el mismo día son dos operaciones legítimas y se fundirían en una sin que
     * nadie se enterara. Una fila sin uid es una operación nueva por definición, que es también
     * la salida para quien copie una fila a mano en la hoja de cálculo y borre su uid.
     *
     * @param uidsToUpdate qué operaciones cambiadas hay que sobrescribir; null si aún no se ha
     *                     preguntado, y entonces cualquier cambio sale como conflicto
     */
    private Incoming classify(Long userId, List<OperationForm> parsed, Set<String> uidsToUpdate) {
        Map<String, Operation> byUid = new HashMap<>();
        for (Operation op : operationRepo.findByUserId(userId)) {
            if (op.getUid() != null && !op.getUid().isBlank()) {
                byUid.put(op.getUid(), op);
            }
        }

        List<OperationForm> nuevas = new ArrayList<>();
        Map<Long, OperationForm> updates = new LinkedHashMap<>();
        List<ImportConflict> conflicts = new ArrayList<>();
        int duplicates = 0;

        for (OperationForm f : parsed) {
            Operation prev = f.getUid() == null ? null : byUid.get(f.getUid());
            if (prev == null) {
                nuevas.add(f);
                continue;
            }
            List<ImportConflict.FieldDiff> diffs = differences(prev, f);
            if (diffs.isEmpty()) {
                duplicates++;
            } else if (uidsToUpdate == null) {
                conflicts.add(new ImportConflict(f.getUid(), prev.getDate(), prev.getTicker(),
                        prev.getAssetName(), diffs));
            } else if (uidsToUpdate.contains(f.getUid())) {
                updates.put(prev.getId(), f);
            }
            // Cambiada y no elegida: el usuario ha dicho que se quede como está.
        }
        return new Incoming(nuevas, updates, conflicts, duplicates);
    }

    /**
     * Campos en los que la fila del fichero no coincide con la operación guardada, ya formateados.
     *
     * <p>Los textos se comparan normalizados igual que al guardar —el ticker en mayúsculas, el
     * resto sin espacios alrededor— porque si no una fila recién exportada chocaría consigo misma.
     * Y los importes por {@code compareTo}, que 100 y 100,00 son el mismo dinero.
     */
    private static List<ImportConflict.FieldDiff> differences(Operation prev, OperationForm f) {
        List<ImportConflict.FieldDiff> diffs = new ArrayList<>();
        diff(diffs, "Fecha", OUT_DATE.format(prev.getDate()), OUT_DATE.format(f.getDate()));
        diff(diffs, "Tipo", prev.getType().name(), f.getType().name());
        diff(diffs, "Ticker", prev.getTicker(), f.getTicker().trim().toUpperCase());
        diff(diffs, "ISIN", prev.getAssetName(), f.getAssetName().trim());
        diff(diffs, "Broker", prev.getBroker(), f.getBroker().trim());
        diffNum(diffs, "Cantidad", prev.getQuantity(), f.getQuantity());
        diffNum(diffs, "Total", prev.getTotal(), f.getTotal());
        diffNum(diffs, "Comisión", prev.getCommission(), f.getCommission());
        diff(diffs, "Grupo AEAT", prev.getAeatGroup().name(), f.getAeatGroup().name());
        diff(diffs, "Notas", prev.getNotes(), f.getNotes());
        diff(diffs, "Traspaso", prev.getTransferId(), f.getTransferId());
        return diffs;
    }

    private static void diff(List<ImportConflict.FieldDiff> diffs, String field,
                             String current, String incoming) {
        String a = current == null ? "" : current.trim();
        String b = incoming == null ? "" : incoming.trim();
        if (!a.equals(b)) {
            diffs.add(new ImportConflict.FieldDiff(field, a, b));
        }
    }

    private static void diffNum(List<ImportConflict.FieldDiff> diffs, String field,
                                BigDecimal current, BigDecimal incoming) {
        BigDecimal a = current == null ? BigDecimal.ZERO : current;
        BigDecimal b = incoming == null ? BigDecimal.ZERO : incoming;
        if (a.compareTo(b) != 0) {
            diffs.add(new ImportConflict.FieldDiff(field, num(a), num(b)));
        }
    }

    /**
     * Los splits que el usuario todavía no tiene. No llevan uid: su identidad es el hecho mismo
     * —un valor se desdobla una vez un día dado—, y un ratio repetido no es un split más sino el
     * mismo contado dos veces, que multiplicaría los títulos por el ratio otra vez.
     */
    private List<SplitForm> newSplits(Long userId, List<SplitForm> parsed) {
        Set<String> existing = splitRepo.findByUserId(userId).stream()
                .map(sp -> splitKey(sp.getTicker(), sp.getDate(), sp.getRatio()))
                .collect(Collectors.toSet());
        // Mutable: quien la recibe todavía tiene que ordenarla por fecha.
        return parsed.stream()
                .filter(f -> existing.add(
                        splitKey(f.getTicker(), f.getDate(), f.getRatio())))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private static String splitKey(String ticker, LocalDate date, BigDecimal ratio) {
        return ticker.trim().toUpperCase() + "|" + date + "|" + ratio.stripTrailingZeros().toPlainString();
    }

    // ─── Importación de Trade Republic ───────────────────────────────────────

    /**
     * Del fichero del broker solo se cargan las compras y ventas; el resto de movimientos no
     * afecta a ninguna posición. Las operaciones ya importadas antes se detectan por su
     * transaction_id y se omiten, así que reimportar el histórico completo es inofensivo.
     */
    private CsvImportResult importTradeRepublic(Long userId, List<List<String>> rows, ImportMode mode) {
        // En REPLACE se parte de cero, así que no hay tickers previos que heredar ni duplicados.
        List<Operation> existing = mode == ImportMode.REPLACE
                ? List.of()
                : operationRepo.findByUserId(userId);

        TradeRepublicParseResult parsed = tradeRepublicService.parse(rows, existing);

        if (!parsed.errors().isEmpty()) {
            return CsvImportResult.failed(parsed.errors());
        }
        // Que no haya nada nuevo no es un error: es lo que pasa al reimportar sin movimientos
        // nuevos, y el usuario no tiene nada que corregir.
        if (parsed.operations().isEmpty() && parsed.duplicates() > 0) {
            return new CsvImportResult(FORMAT_TRADE_REPUBLIC, 0, 0,
                    parsed.ignored(), parsed.duplicates(), List.of(), List.of(), List.of(),
                    List.of());
        }
        if (parsed.operations().isEmpty()) {
            return CsvImportResult.failed(
                    List.of("El fichero no contiene ninguna compra ni venta de valores."));
        }

        if (mode == ImportMode.REPLACE) {
            deleteEverythingOf(userId);
        }

        List<OperationForm> operations = new ArrayList<>(parsed.operations());
        operations.sort(Comparator.comparing(OperationForm::getDate));
        operations.forEach(f -> operationService.save(userId, f));

        log.info("Importadas {} operaciones de Trade Republic para el usuario {} "
                        + "(modo {}, {} movimientos ignorados, {} duplicados)",
                operations.size(), userId, mode, parsed.ignoredCount(), parsed.duplicates());
        return new CsvImportResult(FORMAT_TRADE_REPUBLIC, operations.size(), 0,
                parsed.ignored(), parsed.duplicates(), parsed.pendingValuation(),
                List.of(), List.of(), List.of());
    }

    // ─── Importación de MyInvestor (Inversis) ────────────────────────────────

    /**
     * El extracto trae el histórico completo de la cartera, así que reimportarlo es lo normal:
     * lo que ya está cargado se reconoce y se omite.
     *
     * <p>Las operaciones se dan de alta sin tocar el FIFO y se recalcula una sola vez al final.
     * Con traspasos de por medio no hay alternativa: el coste de un fondo de destino sale de los
     * lotes del de origen, así que hasta que no está el evento entero cargado no hay nada que
     * calcular que signifique algo.
     */
    private CsvImportResult importInversis(Long userId, byte[] content, ImportMode mode) {
        List<Operation> existing = mode == ImportMode.REPLACE
                ? List.of()
                : operationRepo.findByUserId(userId);

        InversisParseResult parsed = inversisService.parse(content, existing);

        if (!parsed.errors().isEmpty()) {
            return CsvImportResult.failed(parsed.errors());
        }
        // Que no haya nada nuevo no es un error: es lo que pasa al reimportar sin movimientos
        // nuevos, y el usuario no tiene nada que corregir.
        if (parsed.operations().isEmpty()) {
            return new CsvImportResult(FORMAT_INVERSIS, 0, 0,
                    parsed.ignored(), parsed.duplicates(), List.of(), List.of(), List.of(),
                    List.of());
        }

        if (mode == ImportMode.REPLACE) {
            deleteEverythingOf(userId);
        }

        List<OperationForm> operations = new ArrayList<>(parsed.operations());
        operations.sort(Comparator.comparing(OperationForm::getDate));
        operations.forEach(f -> operationService.saveDeferred(userId, f));
        fifoService.recalculateAll(userId);

        log.info("Importadas {} operaciones de MyInvestor para el usuario {} "
                        + "(modo {}, {} movimientos ignorados, {} duplicados)",
                operations.size(), userId, mode, parsed.ignoredCount(), parsed.duplicates());
        return new CsvImportResult(FORMAT_INVERSIS, operations.size(), 0,
                parsed.ignored(), parsed.duplicates(), List.of(),
                parsed.transferWarnings(), List.of(), List.of());
    }

    private void deleteEverythingOf(Long userId) {
        saleRecordRepo.deleteByUserId(userId);
        fifoLotRepo.deleteByUserId(userId);
        operationRepo.deleteByUserId(userId);
        splitRepo.deleteByUserId(userId);
    }

    private void parseRow(List<String> row, List<OperationForm> operations, List<SplitForm> splits) {
        LocalDate date = date(row.get(0));
        String type = row.get(1).trim().toUpperCase();
        String ticker = required(row.get(2), "Ticker");

        if (SPLIT.equals(type)) {
            SplitForm f = new SplitForm();
            f.setDate(date);
            f.setTicker(ticker);
            f.setRatio(positive(row.get(5), "Cantidad (ratio del split)"));
            splits.add(f);
            return;
        }

        OperationType opType;
        try {
            opType = OperationType.valueOf(type);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Tipo '" + row.get(1).trim() + "' no válido. Use BUY, SELL, CANJE, "
                            + "TRASPASO_OUT, TRASPASO_IN o SPLIT.");
        }

        OperationForm f = new OperationForm();
        f.setDate(date);
        f.setType(opType);
        f.setTicker(ticker);
        f.setAssetName(required(row.get(3), "ISIN"));
        f.setBroker(required(row.get(4), "Broker"));
        f.setQuantity(positive(row.get(5), "Cantidad"));
        f.setAeatGroup(aeatGroup(row.get(8)));
        f.setNotes(blankToNull(row.get(9)));
        f.setTransferId(row.size() > COL_TRANSFER ? blankToNull(row.get(COL_TRANSFER)) : null);
        f.setUid(row.size() > COL_UID ? blankToNull(row.get(COL_UID)) : null);

        if (opType == OperationType.CANJE) {
            // Acciones liberadas: sin coste ni comisión (LIRPF Art. 37.1.a)
            f.setTotal(BigDecimal.ZERO);
            f.setCommission(BigDecimal.ZERO);
        } else {
            f.setTotal(positive(row.get(6), "Total"));
            BigDecimal commission = decimal(row.get(7), "Comision");
            if (commission == null) commission = BigDecimal.ZERO;
            if (commission.signum() < 0) {
                throw new IllegalArgumentException("La comisión no puede ser negativa.");
            }
            f.setCommission(commission);
        }
        operations.add(f);
    }

    // ─── Validación de campos ────────────────────────────────────────────────

    private static String required(String raw, String field) {
        String v = raw == null ? "" : raw.trim();
        if (v.isEmpty()) throw new IllegalArgumentException(field + " es obligatorio.");
        return v;
    }

    private static String blankToNull(String raw) {
        String v = raw == null ? "" : raw.trim();
        return v.isEmpty() ? null : v;
    }

    private static LocalDate date(String raw) {
        String v = required(raw, "Fecha");
        try {
            return v.contains("/") ? LocalDate.parse(v, OUT_DATE) : LocalDate.parse(v);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Fecha '" + v + "' no válida. Use el formato dd/MM/yyyy.");
        }
    }

    /** Acepta coma o punto como separador decimal. Devuelve null si viene vacío. */
    private static BigDecimal decimal(String raw, String field) {
        String v = raw == null ? "" : raw.trim();
        if (v.isEmpty()) return null;
        try {
            return new BigDecimal(v.replace(",", "."));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    field + ": '" + v + "' no es un número. Use coma o punto decimal, "
                            + "sin separador de miles.");
        }
    }

    private static BigDecimal positive(String raw, String field) {
        BigDecimal v = decimal(raw, field);
        if (v == null) throw new IllegalArgumentException(field + " es obligatorio.");
        if (v.signum() <= 0) throw new IllegalArgumentException(field + " debe ser mayor que 0.");
        return v;
    }

    private static AeatGroup aeatGroup(String raw) {
        String v = required(raw, "Grupo AEAT").toUpperCase();
        if (v.length() == 1 && Character.isDigit(v.charAt(0))) v = "GROUP_" + v;
        try {
            return AeatGroup.valueOf(v);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Grupo AEAT '" + raw.trim() + "' no válido. Use GROUP_1, GROUP_2 o GROUP_3.");
        }
    }

    // ─── CSV: escritura y lectura ────────────────────────────────────────────

    private static String num(BigDecimal v) {
        if (v == null) return "";
        return v.stripTrailingZeros().toPlainString().replace('.', ',');
    }

    private static String escape(String v) {
        if (v == null || v.isEmpty()) return "";
        boolean needsQuotes = v.indexOf(SEP) >= 0 || v.indexOf('"') >= 0
                || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0;
        return needsQuotes ? '"' + v.replace("\"", "\"\"") + '"' : v;
    }

    private static String stripBom(String s) {
        return s.startsWith("﻿") ? s.substring(1) : s;
    }

    private boolean looksLikeHeader(List<String> row) {
        return !row.isEmpty() && row.get(0).trim().equalsIgnoreCase("Fecha");
    }

    /**
     * Lector CSV que respeta las comillas dobles, de forma que un campo entrecomillado
     * puede contener el separador, comillas escapadas ("") o saltos de línea.
     */
    static List<List<String>> parse(String text) {
        return parse(text, SEP);
    }

    static List<List<String>> parse(String text, char sep) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == sep) {
                row.add(field.toString());
                field.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(field.toString());
                field.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                field.append(c);
            }
        }
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        // Descartar filas totalmente vacías del final
        rows.removeIf(r -> r.stream().allMatch(String::isBlank));
        return rows;
    }
}
