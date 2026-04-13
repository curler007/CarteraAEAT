package com.raul.bolsa.web;

import com.raul.bolsa.domain.AeatGroup;
import com.raul.bolsa.domain.Operation;
import com.raul.bolsa.domain.OperationType;
import com.raul.bolsa.domain.SaleRecord;
import com.raul.bolsa.repository.FifoLotRepository;
import com.raul.bolsa.repository.OperationRepository;
import com.raul.bolsa.repository.SaleRecordRepository;
import com.raul.bolsa.service.OperationService;
import com.raul.bolsa.web.dto.OperationForm;
import com.raul.bolsa.web.dto.TickerInfo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
@RequiredArgsConstructor
public class OperationController {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final OperationRepository operationRepo;
    private final FifoLotRepository fifoLotRepo;
    private final SaleRecordRepository saleRecordRepo;
    private final OperationService operationService;

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

        model.addAttribute("portfolio", portfolio);
        model.addAttribute("portfolioPercent", portfolioPercent);
        model.addAttribute("recentOps", operationRepo.findAllByOrderByDateDescIdDesc()
                .stream().limit(10).toList());
        return "dashboard";
    }

    @GetMapping("/operations")
    public String list(Model model) {
        List<Operation> operations = operationRepo.findAllByOrderByDateDescIdDesc();
        model.addAttribute("operations", operations);

        // CSS class por operationId para sombrear compras según consumo del lote:
        //   verde  → totalmente consumida (remainingQty == 0)
        //   amarillo → parcialmente consumida (0 < remaining < initial)
        //   vacío  → sin consumir
        Map<Long, String> lotRowClass = fifoLotRepo.findAll().stream()
                .collect(Collectors.toMap(
                        lot -> lot.getOperation().getId(),
                        lot -> {
                            if (lot.getRemainingQty().compareTo(BigDecimal.ZERO) == 0)
                                return "table-secondary";
                            if (lot.getRemainingQty().compareTo(lot.getInitialQty()) < 0)
                                return "table-warning";
                            return "";
                        },
                        (a, b) -> a
                ));
        model.addAttribute("lotRowClass", lotRowClass);

        // Contador acumulado de títulos por ticker tras cada operación (orden cronológico ASC)
        List<Operation> chronological = operations.stream()
                .sorted(java.util.Comparator.comparing(Operation::getDate)
                        .thenComparingLong(Operation::getId))
                .toList();
        Map<String, BigDecimal> runningByTicker = new HashMap<>();
        Map<Long, BigDecimal> runningBalance = new HashMap<>();
        for (Operation op : chronological) {
            String ticker = op.getTicker().toUpperCase();
            BigDecimal current = runningByTicker.getOrDefault(ticker, BigDecimal.ZERO);
            if (op.getType() == OperationType.SELL) {
                current = current.subtract(op.getQuantity());
            } else {
                current = current.add(op.getQuantity());
            }
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
                                        + ": " + formatQty(sr.getQuantity()))
                                .collect(Collectors.joining("<br>"))
                ));
        model.addAttribute("sellTooltip", sellTooltip);

        return "operations/list";
    }

    private String formatQty(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
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
