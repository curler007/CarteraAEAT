package com.raul.bolsa.web;

import com.raul.bolsa.domain.SaleRecord;
import com.raul.bolsa.repository.SaleRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.raul.bolsa.web.dto.TickerSaleGroup;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Controller
@RequiredArgsConstructor
public class ReportController {

    private final SaleRecordRepository saleRecordRepo;

    @GetMapping("/sales")
    public String sales(@RequestParam(defaultValue = "0") int year, Model model) {
        int selectedYear = year > 0 ? year : LocalDate.now().getYear() - 1;
        List<SaleRecord> records = saleRecordRepo.findByTaxYearOrderBySaleDateAscTickerAsc(selectedYear);

        BigDecimal totalCost     = records.stream().map(SaleRecord::getCostBasis).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalProceeds = records.stream().map(SaleRecord::getProceeds).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalGains    = records.stream().map(SaleRecord::getGainLoss)
                .filter(g -> g.signum() > 0).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalLosses   = records.stream().map(SaleRecord::getGainLoss)
                .filter(g -> g.signum() < 0).reduce(BigDecimal.ZERO, BigDecimal::add).abs();

        List<TickerSaleGroup> groups = records.stream()
                .collect(Collectors.groupingBy(SaleRecord::getTicker))
                .entrySet().stream()
                .map(e -> {
                    List<SaleRecord> recs = e.getValue();
                    BigDecimal gQty      = recs.stream().map(SaleRecord::getQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
                    BigDecimal gCost     = recs.stream().map(SaleRecord::getCostBasis).reduce(BigDecimal.ZERO, BigDecimal::add);
                    BigDecimal gProceeds = recs.stream().map(SaleRecord::getProceeds).reduce(BigDecimal.ZERO, BigDecimal::add);
                    BigDecimal gGains    = recs.stream().map(SaleRecord::getGainLoss)
                            .filter(g -> g.signum() > 0).reduce(BigDecimal.ZERO, BigDecimal::add);
                    BigDecimal gLosses   = recs.stream().map(SaleRecord::getGainLoss)
                            .filter(g -> g.signum() < 0).reduce(BigDecimal.ZERO, BigDecimal::add).abs();
                    SaleRecord first = recs.get(0);
                    return new TickerSaleGroup(first.getTicker(), first.getAssetName(),
                            first.getAeatGroup().getLabel(), gQty, gCost, gProceeds, gGains, gLosses, recs);
                })
                .sorted(Comparator.comparing(TickerSaleGroup::ticker))
                .toList();

        model.addAttribute("records", records);
        model.addAttribute("totalCost", totalCost);
        model.addAttribute("totalProceeds", totalProceeds);
        model.addAttribute("totalGains", totalGains);
        model.addAttribute("totalLosses", totalLosses);
        model.addAttribute("groups", groups);
        model.addAttribute("selectedYear", selectedYear);
        model.addAttribute("years", availableYears());
        return "sales/list";
    }

    @GetMapping("/sales/export.csv")
    public ResponseEntity<byte[]> exportCsv(@RequestParam int year) {
        List<SaleRecord> records = saleRecordRepo.findByTaxYearOrderBySaleDateAscTickerAsc(year);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        // BOM UTF-8 para que Excel lo abra bien
        baos.writeBytes(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});

        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8))) {
            pw.println("Valor;Grupo AEAT;Fecha adquisicion;Fecha transmision;" +
                       "Cantidad;Valor adquisicion (EUR);Valor transmision (EUR);Ganancia/Perdida (EUR);" +
                       "BC;BV");

            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd/MM/yyyy");
            for (SaleRecord r : records) {
                pw.printf("%s;%s;%s;%s;%s;%s;%s;%s;%s;%s%n",
                        r.getAssetName(),
                        r.getAeatGroup().name(),
                        r.getPurchaseDate().format(fmt),
                        r.getSaleDate().format(fmt),
                        r.getQuantity().toPlainString(),
                        r.getCostBasis().toPlainString(),
                        r.getProceeds().toPlainString(),
                        r.getGainLoss().toPlainString(),
                        r.getBuyBroker(),
                        r.getSellBroker());
            }
        }

        String filename = "aeat_ventas_" + year + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(baos.toByteArray());
    }

    private List<Integer> availableYears() {
        int current = LocalDate.now().getYear();
        return List.of(current, current - 1, current - 2, current - 3);
    }
}
