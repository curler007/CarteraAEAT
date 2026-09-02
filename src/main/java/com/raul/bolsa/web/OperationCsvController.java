package com.raul.bolsa.web;

import com.raul.bolsa.security.CurrentUser;
import com.raul.bolsa.service.OperationCsvService;
import com.raul.bolsa.web.dto.CsvImportResult;
import com.raul.bolsa.web.dto.ImportMode;
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

/**
 * Exportación e importación de la cartera del usuario en CSV.
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class OperationCsvController {

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
                + "05/08/2025;BUY;APPLE;US0378331005;Trade Republic;2,826455;501;1;GROUP_3;\n"
                + "14/08/2025;SELL;APPLE;US0378331005;Trade Republic;1,5;300,25;1;GROUP_3;venta parcial\n"
                + "10/06/2024;SPLIT;NVIDIA;;;10;;;;split 1:10\n";
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
                           Model model,
                           RedirectAttributes flash) {
        model.addAttribute("header", OperationCsvService.HEADER);

        if (file == null || file.isEmpty()) {
            model.addAttribute("errors", List.of("Selecciona un fichero CSV."));
            return "operations/import";
        }

        CsvImportResult result;
        try {
            result = csvService.importCsv(currentUser.id(), file.getBytes(), mode);
        } catch (IOException e) {
            log.warn("No se pudo leer el CSV subido: {}", e.getMessage());
            model.addAttribute("errors", List.of("No se ha podido leer el fichero: " + e.getMessage()));
            return "operations/import";
        }

        if (!result.ok()) {
            model.addAttribute("errors", result.errors());
            model.addAttribute("mode", mode);
            return "operations/import";
        }

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
        flash.addFlashAttribute("success", msg.toString());

        // Entraron acciones sin contrapartida en efectivo: mientras valgan cero, venderlas
        // computaría como ganancia íntegra, así que hay que pedir su valor de adquisición.
        if (!result.pendingValuation().isEmpty()) {
            flash.addFlashAttribute("pendingValuation", result.pendingValuation());
        }
        return "redirect:/operations";
    }
}
