package com.raul.bolsa.web;

import com.raul.bolsa.domain.AeatGroup;
import com.raul.bolsa.domain.Operation;
import com.raul.bolsa.domain.OperationType;
import com.raul.bolsa.domain.SaleRecord;
import com.raul.bolsa.repository.FifoLotRepository;
import com.raul.bolsa.repository.OperationRepository;
import com.raul.bolsa.repository.SaleRecordRepository;
import com.raul.bolsa.repository.SplitRepository;
import com.raul.bolsa.service.OperationService;
import com.raul.bolsa.web.dto.HistoryRow;
import com.raul.bolsa.web.dto.OperationForm;
import com.raul.bolsa.web.dto.SaleYearSummary;
import com.raul.bolsa.web.dto.TickerInfo;
import com.raul.bolsa.web.dto.TickerYearResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Controller
@RequiredArgsConstructor
public class OperationController {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final OperationRepository operationRepo;
    private final FifoLotRepository fifoLotRepo;
    private final SaleRecordRepository saleRecordRepo;
    private final SplitRepository splitRepo;
    private final OperationService operationService;
    private final com.raul.bolsa.service.SplitService splitService;

    @GetMapping("/")
    public String root() {
        return "redirect:/dashboard";
    }

    @GetMapping("/dashboard")
    public String dashboard(Model model) {
        List<com.raul.bolsa.web.dto.PortfolioItem> portfolio = fifoLotRepo.findPortfolioSummary();

        BigDecimal totalCost = portfolio.stream()
                .map(com.raul.bolsa.web.dto.PortfolioItem::totalCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, BigDecimal> portfolioPercent = portfolio.stream()
                .collect(Collectors.toMap(
                        com.raul.bolsa.web.dto.PortfolioItem::ticker,
                        item -> totalCost.compareTo(BigDecimal.ZERO) == 0
                                ? BigDecimal.ZERO
                                : item.totalCost()
                                        .multiply(new BigDecimal("100"))
                                        .divide(totalCost, 2, java.math.RoundingMode.HALF_UP)
                ));

        // Ventas agrupadas por año y ticker para el resumen del dashboard
        List<SaleYearSummary> salesByYear = saleRecordRepo.findAll().stream()
                .collect(Collectors.groupingBy(
                        SaleRecord::getTaxYear,
                        TreeMap::new,
                        Collectors.toList()))
                .entrySet().stream()
                .sorted(Map.Entry.<Integer, List<com.raul.bolsa.domain.SaleRecord>>comparingByKey().reversed())
                .map(yearEntry -> {
                    int year = yearEntry.getKey();
                    List<com.raul.bolsa.domain.SaleRecord> yearRecords = yearEntry.getValue();

                    List<TickerYearResult> tickers = yearRecords.stream()
                            .collect(Collectors.groupingBy(
                                    sr -> sr.getTicker(),
                                    TreeMap::new,
                                    Collectors.toList()))
                            .entrySet().stream()
                            .map(tickerEntry -> {
                                List<com.raul.bolsa.domain.SaleRecord> recs = tickerEntry.getValue();
                                String assetName = recs.get(0).getAssetName();
                                BigDecimal cost = recs.stream()
                                        .map(com.raul.bolsa.domain.SaleRecord::getCostBasis)
                                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                                BigDecimal gl = recs.stream()
                                        .map(com.raul.bolsa.domain.SaleRecord::getGainLoss)
                                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                                BigDecimal pct = cost.compareTo(BigDecimal.ZERO) == 0
                                        ? BigDecimal.ZERO
                                        : gl.multiply(BigDecimal.valueOf(100))
                                                .divide(cost, 2, RoundingMode.HALF_UP);
                                return new TickerYearResult(tickerEntry.getKey(), assetName, cost, gl, pct);
                            })
                            .sorted(Comparator.comparing(TickerYearResult::gainLossPercent).reversed())
                            .toList();

                    BigDecimal yearCost = yearRecords.stream()
                            .map(com.raul.bolsa.domain.SaleRecord::getCostBasis)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    BigDecimal yearGl = yearRecords.stream()
                            .map(com.raul.bolsa.domain.SaleRecord::getGainLoss)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    BigDecimal yearPct = yearCost.compareTo(BigDecimal.ZERO) == 0
                            ? BigDecimal.ZERO
                            : yearGl.multiply(BigDecimal.valueOf(100))
                                    .divide(yearCost, 2, RoundingMode.HALF_UP);

                    return new SaleYearSummary(year, tickers, yearGl, yearCost, yearPct);
                })
                .toList();

        model.addAttribute("portfolio", portfolio);
        model.addAttribute("portfolioPercent", portfolioPercent);
        model.addAttribute("totalCost", totalCost);
        model.addAttribute("salesByYear", salesByYear);
        return "dashboard";
    }

    @GetMapping("/operations")
    public String list(Model model) {
        List<Operation> operations = operationRepo.findAllByOrderByDateDescIdDesc();

        // CSS class por operationId para sombrear compras según consumo del lote:
        //   verde  → totalmente consumida (remainingQty == 0)
        //   amarillo → parcialmente consumida (0 < remaining < initial)
        //   vacío  → sin consumir
        List<com.raul.bolsa.domain.FifoLot> allLots = fifoLotRepo.findAll();

        Map<Long, String> lotRowClass = allLots.stream()
                .collect(Collectors.toMap(
                        lot -> lot.getOperation().getId(),
                        lot -> {
                            if (lot.getRemainingQty().compareTo(BigDecimal.ZERO) == 0)
                                return "table-secondary";
                            // Comparar costes, no cantidades: tras un split remainingQty > initialQty
                            if (lot.getRemainingCost().compareTo(lot.getInitialCost()) < 0)
                                return "table-warning";
                            return "";
                        },
                        (a, b) -> a
                ));
        model.addAttribute("lotRowClass", lotRowClass);

        Map<Long, BigDecimal> lotRemainingQty = allLots.stream()
                .collect(Collectors.toMap(
                        lot -> lot.getOperation().getId(),
                        lot -> lot.getRemainingQty(),
                        (a, b) -> a
                ));
        model.addAttribute("lotRemainingQty", lotRemainingQty);

        Map<Long, BigDecimal> lotRemainingCost = allLots.stream()
                .collect(Collectors.toMap(
                        lot -> lot.getOperation().getId(),
                        lot -> lot.getRemainingCost(),
                        (a, b) -> a
                ));
        model.addAttribute("lotRemainingCost", lotRemainingCost);

        // Cargar splits una sola vez y agrupar por ticker (reutilizado en running balance e history)
        List<com.raul.bolsa.domain.Split> allSplits = splitRepo.findAll();
        Map<String, List<com.raul.bolsa.domain.Split>> splitsByTicker = allSplits.stream()
                .collect(Collectors.groupingBy(s -> s.getTicker().toUpperCase()));

        // Saldo acumulado por ticker tras cada operación, expresado en acciones actuales.
        // Cada qty se multiplica por el factor acumulado de splits ocurridos después de su fecha,
        // de forma que el saldo refleja siempre las acciones en términos post-split vigentes.
        java.time.LocalDate today = java.time.LocalDate.now();
        List<Operation> chronological = operations.stream()
                .sorted(Comparator.comparing(Operation::getDate)
                        .thenComparingLong(Operation::getId))
                .toList();
        Map<String, BigDecimal> runningByTicker = new HashMap<>();
        Map<Long, BigDecimal> runningBalance = new HashMap<>();
        for (Operation op : chronological) {
            String ticker = op.getTicker().toUpperCase();
            List<com.raul.bolsa.domain.Split> tickerSplits =
                    splitsByTicker.getOrDefault(ticker, List.of());
            BigDecimal factor = splitService.cumulativeFactor(tickerSplits, op.getDate(), today);
            BigDecimal adjustedQty = op.getQuantity().multiply(factor);
            BigDecimal current = runningByTicker.getOrDefault(ticker, BigDecimal.ZERO);
            current = op.getType() == OperationType.SELL
                    ? current.subtract(adjustedQty)
                    : current.add(adjustedQty);
            runningByTicker.put(ticker, current);
            runningBalance.put(op.getId(), current);
        }
        model.addAttribute("runningBalance", runningBalance);

        // Tooltip para ventas: mapa sellOpId → HTML con los lotes consumidos
        Map<Long, String> sellTooltip = saleRecordRepo.findAll().stream()
                .collect(Collectors.groupingBy(sr -> sr.getSellOperation().getId()))
                .entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> e.getValue().stream()
                                .sorted(Comparator.comparing(SaleRecord::getPurchaseDate))
                                .map(sr -> DATE_FMT.format(sr.getPurchaseDate())
                                        + ": " + sr.getQuantity().stripTrailingZeros().toPlainString())
                                .collect(Collectors.joining("<br>"))
                ));
        model.addAttribute("sellTooltip", sellTooltip);

        // Lista unificada de operaciones + splits ordenada por fecha DESC
        List<HistoryRow> history = new ArrayList<>();
        operations.forEach(op -> history.add(new HistoryRow(op.getDate(), op, null)));
        allSplits.forEach(s -> history.add(new HistoryRow(s.getDate(), null, s)));
        history.sort((a, b) -> {
            int cmp = b.date().compareTo(a.date());
            if (cmp != 0) return cmp;
            // Mismo día: splits antes que operaciones
            if (a.split() != null && b.split() == null) return -1;
            if (a.split() == null && b.split() != null) return 1;
            if (a.split() != null) return b.split().getId().compareTo(a.split().getId());
            return b.operation().getId().compareTo(a.operation().getId());
        });
        model.addAttribute("history", history);

        return "operations/list";
    }

    @GetMapping("/operations/new")
    public String newForm(Model model) {
        model.addAttribute("form", new OperationForm());
        addFormEnums(model);
        return "operations/form";
    }

    @PostMapping("/operations")
    public String save(@Valid @ModelAttribute("form") OperationForm form,
                       BindingResult result,
                       Model model,
                       RedirectAttributes flash) {
        if (form.getType() != OperationType.CANJE
                && (form.getTotal() == null || form.getTotal().compareTo(BigDecimal.ZERO) <= 0)) {
            result.rejectValue("total", "invalid", "El coste total debe ser mayor que 0");
        }
        if (result.hasErrors()) {
            addFormEnums(model);
            return "operations/form";
        }
        try {
            operationService.save(form);
            flash.addFlashAttribute("success", "Operación registrada correctamente.");
        } catch (IllegalStateException e) {
            model.addAttribute("error", e.getMessage());
            addFormEnums(model);
            return "operations/form";
        }
        return "redirect:/operations";
    }

    @GetMapping("/operations/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        Operation op = operationRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Operación no encontrada: " + id));
        model.addAttribute("form", toForm(op));
        model.addAttribute("editId", id);
        addFormEnums(model);
        return "operations/form";
    }

    @PostMapping("/operations/{id}/edit")
    public String update(@PathVariable Long id,
                         @Valid @ModelAttribute("form") OperationForm form,
                         BindingResult result,
                         Model model,
                         RedirectAttributes flash) {
        if (form.getType() != OperationType.CANJE
                && (form.getTotal() == null || form.getTotal().compareTo(BigDecimal.ZERO) <= 0)) {
            result.rejectValue("total", "invalid", "El coste total debe ser mayor que 0");
        }
        if (result.hasErrors()) {
            model.addAttribute("editId", id);
            addFormEnums(model);
            return "operations/form";
        }
        try {
            operationService.update(id, form);
            flash.addFlashAttribute("success", "Operación actualizada correctamente.");
        } catch (IllegalStateException e) {
            model.addAttribute("editId", id);
            model.addAttribute("error", e.getMessage());
            addFormEnums(model);
            return "operations/form";
        }
        return "redirect:/operations";
    }

    @PostMapping("/operations/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes flash) {
        try {
            operationService.delete(id);
            flash.addFlashAttribute("success", "Operación eliminada.");
        } catch (IllegalStateException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/operations";
    }

    /**
     * Devuelve un mapa ticker → ticker de valor con todos los tickers conocidos.
     * Usado por el formulario para autocompletar el nombre al escribir el ticker.
     */
    @GetMapping("/operations/ticker-names")
    @ResponseBody
    public Map<String, TickerInfo> tickerNames() {
        return operationRepo.findAll().stream()
                .collect(Collectors.toMap(
                        op -> op.getTicker().toUpperCase(),
                        op -> new TickerInfo(op.getAssetName(), op.getAeatGroup().name()),
                        (existing, replacement) -> existing   // si hay duplicados, queda el primero
                ));
    }

    private OperationForm toForm(Operation op) {
        OperationForm f = new OperationForm();
        f.setDate(op.getDate());
        f.setBroker(op.getBroker());
        f.setType(op.getType());
        f.setTicker(op.getTicker());
        f.setAssetName(op.getAssetName());
        f.setQuantity(op.getQuantity());
        f.setTotal(op.getTotal());
        f.setCommission(op.getCommission());
        f.setAeatGroup(op.getAeatGroup());
        f.setNotes(op.getNotes());
        return f;
    }

    private void addFormEnums(Model model) {
        model.addAttribute("operationTypes", OperationType.values());
        model.addAttribute("aeatGroups", AeatGroup.values());
    }
}
