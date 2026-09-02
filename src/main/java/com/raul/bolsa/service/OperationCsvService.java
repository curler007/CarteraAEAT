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
import java.util.List;
import java.util.Map;

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
            "Fecha;Tipo;Ticker;ISIN;Broker;Cantidad;Total;Comision;Grupo AEAT;Notas";

    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final DateTimeFormatter OUT_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final char SEP = ';';
    private static final int COLUMNS = 10;

    /** Tipo reservado para las filas de split; el resto son valores de OperationType. */
    private static final String SPLIT = "SPLIT";

    public static final String FORMAT_OWN = "formato propio";
    public static final String FORMAT_TRADE_REPUBLIC = "Trade Republic";

    private final OperationRepository operationRepo;
    private final SplitRepository splitRepo;
    private final FifoLotRepository fifoLotRepo;
    private final SaleRecordRepository saleRecordRepo;
    private final OperationService operationService;
    private final SplitService splitService;
    private final TradeRepublicCsvService tradeRepublicService;

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
                    op.getNotes()
            });
        }
        for (Split s : splitRepo.findByUserId(userId)) {
            rows.add(new String[]{
                    OUT_DATE.format(s.getDate()), SPLIT, s.getTicker(),
                    "", "", num(s.getRatio()), "", "", "", ""
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
            if (row.size() != COLUMNS) {
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

        if (mode == ImportMode.REPLACE) {
            deleteEverythingOf(userId);
        }

        // Las operaciones primero y los splits después, en orden cronológico: es el mismo
        // camino que valida ReplayConsistencyTest.
        operations.sort(Comparator.comparing(OperationForm::getDate));
        operations.forEach(f -> operationService.save(userId, f));
        splits.sort(Comparator.comparing(SplitForm::getDate));
        splits.forEach(f -> splitService.save(userId, f));

        log.info("Importadas {} operaciones y {} splits para el usuario {} (modo {})",
                operations.size(), splits.size(), userId, mode);
        return new CsvImportResult(FORMAT_OWN, operations.size(), splits.size(),
                Map.of(), 0, List.of());
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
                    parsed.ignored(), parsed.duplicates(), List.of());
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
                parsed.ignored(), parsed.duplicates(), List.of());
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
                    "Tipo '" + row.get(1).trim() + "' no válido. Use BUY, SELL, CANJE o SPLIT.");
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
