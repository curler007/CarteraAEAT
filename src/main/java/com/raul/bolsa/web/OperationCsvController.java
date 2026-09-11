package com.raul.bolsa.web;

import com.raul.bolsa.security.CurrentUser;
import com.raul.bolsa.service.OperationCsvService;
import com.raul.bolsa.web.dto.CsvImportResult;
import com.raul.bolsa.web.dto.ImportMode;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * Exportación e importación de la cartera del usuario en CSV.
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class OperationCsvController {

    /**
     * Dónde espera el fichero mientras el usuario decide qué hacer con las operaciones que
     * vienen cambiadas. No se puede pedir que lo vuelva a elegir: el navegador no rellena un
     * input de fichero por su cuenta, y hacerle repetir la subida para confirmar sería absurdo.
     */
    private static final String PENDING_FILE = "importPendingFile";
    private static final String PENDING_MODE = "importPendingMode";

    private final OperationCsvService csvService;
    private final CurrentUser currentUser;

    @GetMapping("/operations/export.csv")
    public ResponseEntity<byte[]> export() {
        byte[] body = csvService.export(currentUser.id());
        String filename = "operaciones_" + currentUser.username() + "_" + LocalDate.now() + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(body);
    }

    /** Fichero de ejemplo con la cabecera y un par de filas, para ver el formato exacto. */
    @GetMapping("/operations/import/ejemplo.csv")
    public ResponseEntity<byte[]> sample() {
        String sample = "﻿" + OperationCsvService.HEADER + "\n"
                + "05/08/2025;BUY;APPLE;US0378331005;Trade Republic;2,826455;501;1;GROUP_3;;\n"
                + "14/08/2025;SELL;APPLE;US0378331005;Trade Republic;1,5;300,25;1;GROUP_3;venta parcial;\n"
                + "10/06/2024;SPLIT;NVIDIA;;;10;;;;split 1:10;\n"
                + "06/03/2026;TRASPASO_OUT;IE00BYX5MD61;IE00BYX5MD61;MyInvestor;12,5;110,4;0;GROUP_2;;T1\n"
                + "09/03/2026;TRASPASO_IN;IE000N4ZYX28;IE000N4ZYX28;MyInvestor;9,87;110,4;0;GROUP_2;;T1\n";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"ejemplo_operaciones.csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(sample.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** "CASH / DIVIDEND: 22, CASH / TRANSFER_INBOUND: 4, ..." */
    private static String describe(java.util.Map<String, Integer> ignored) {
        return ignored.entrySet().stream()
                .map(e -> e.getKey() + ": " + e.getValue())
                .collect(java.util.stream.Collectors.joining(", "));
    }

    @GetMapping("/operations/import")
    public String importForm(Model model) {
        model.addAttribute("header", OperationCsvService.HEADER);
        return "operations/import";
    }

    @PostMapping("/operations/import")
    public String doImport(@RequestParam("file") MultipartFile file,
                           @RequestParam(defaultValue = "ADD") ImportMode mode,
                           HttpSession session,
                           Model model,
                           RedirectAttributes flash) {
        model.addAttribute("header", OperationCsvService.HEADER);

        if (file == null || file.isEmpty()) {
            model.addAttribute("errors", List.of("Selecciona un fichero CSV."));
            return "operations/import";
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            log.warn("No se pudo leer el CSV subido: {}", e.getMessage());
            model.addAttribute("errors", List.of("No se ha podido leer el fichero: " + e.getMessage()));
            return "operations/import";
        }
        CsvImportResult result = csvService.importCsv(currentUser.id(), bytes, mode);

        if (result.needsDecision()) {
            session.setAttribute(PENDING_FILE, bytes);
            session.setAttribute(PENDING_MODE, mode);
            model.addAttribute("conflicts", result.conflicts());
            model.addAttribute("mode", mode);
            return "operations/import-conflicts";
        }

        if (!result.ok()) {
            model.addAttribute("errors", result.errors());
            model.addAttribute("mode", mode);
            return "operations/import";
        }

        return finish(result, flash);
    }

    /** Cuenta lo que ha entrado y devuelve al listado. Común a la importación y a su confirmación. */
    private String finish(CsvImportResult result, RedirectAttributes flash) {
        StringBuilder msg = new StringBuilder(String.format(
                "Importación completada (%s): %d operaciones", result.format(), result.operations()));
        if (result.splits() > 0) msg.append(" y ").append(result.splits()).append(" splits");
        msg.append(result.operations() > 0 || result.splits() > 0
                ? ". El FIFO se ha recalculado."
                : ": tu cartera ya estaba al día.");
        if (result.duplicates() > 0) {
            msg.append(String.format(
                    " Se han omitido %d operaciones que ya estaban en tu cartera.",
                    result.duplicates()));
        }
        if (result.ignoredCount() > 0) {
            msg.append(String.format(" Ignorados %d movimientos que no son compras ni ventas (%s).",
                    result.ignoredCount(), describe(result.ignored())));
        }
        // Las entregas sin coste no se avisan aquí: el listado y el dashboard las muestran de
        // forma permanente mientras sigan a cero, que es cuando dejan de importar.
        if (!result.pendingValuation().isEmpty()) {
            msg.append(String.format(" %d entraron sin coste y hay que valorarlas.",
                    result.pendingValuation().size()));
        }
        if (!result.warnings().isEmpty()) {
            msg.append(" Aviso: ").append(String.join("; ", result.warnings())).append('.');
        }
        flash.addFlashAttribute("success", msg.toString());
        return "redirect:/operations";
    }

    /**
     * Segundo paso de una importación que traía operaciones cambiadas: se repite con las
     * decisiones tomadas.
     *
     * <p>El fichero se reprocesa entero en vez de guardarse lo ya calculado. Cuesta lo mismo y
     * evita el problema de fondo de partir una escritura en dos: entre la pregunta y la respuesta
     * la cartera ha podido cambiar —otra pestaña, otra importación—, y aplicar un plan hecho
     * sobre datos viejos escribiría sobre algo que ya no es lo que se enseñó.
     *
     * @param update uid de cada operación que el usuario ha elegido sobrescribir; las demás se
     *               quedan como están
     */
    @PostMapping("/operations/import/resolver")
    public String resolveConflicts(@RequestParam(name = "update", required = false) Set<String> update,
                                   HttpSession session,
                                   Model model,
                                   RedirectAttributes flash) {
        byte[] bytes = (byte[]) session.getAttribute(PENDING_FILE);
        ImportMode mode = (ImportMode) session.getAttribute(PENDING_MODE);
        if (bytes == null || mode == null) {
            flash.addFlashAttribute("error",
                    "La importación ha caducado. Vuelve a subir el fichero.");
            return "redirect:/operations/import";
        }
        session.removeAttribute(PENDING_FILE);
        session.removeAttribute(PENDING_MODE);

        CsvImportResult result = csvService.importCsv(
                currentUser.id(), bytes, mode, update == null ? Set.of() : update);

        if (!result.ok()) {
            model.addAttribute("header", OperationCsvService.HEADER);
            model.addAttribute("errors", result.errors().isEmpty()
                    ? List.of("El fichero ha cambiado y vuelve a haber decisiones pendientes.")
                    : result.errors());
            return "operations/import";
        }
        return finish(result, flash);
    }
}
