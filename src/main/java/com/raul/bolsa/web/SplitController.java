package com.raul.bolsa.web;

import com.raul.bolsa.domain.Split;
import com.raul.bolsa.repository.SplitRepository;
import com.raul.bolsa.service.SplitDetectionService;
import com.raul.bolsa.service.SplitService;
import com.raul.bolsa.web.dto.DetectedSplit;
import com.raul.bolsa.web.dto.SplitForm;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Controller
@RequiredArgsConstructor
public class SplitController {

    private final SplitRepository splitRepo;
    private final SplitService splitService;
    private final SplitDetectionService splitDetectionService;
    private final com.raul.bolsa.security.CurrentUser currentUser;

    @GetMapping("/splits")
    public String list(Model model) {
        model.addAttribute("splits", splitRepo.findByUserId(
                currentUser.id(), Sort.by(Sort.Direction.DESC, "date", "id")));
        return "splits/list";
    }

    /**
     * Splits publicados por Yahoo que afectan a la cartera y no están registrados.
     * La tabla de la página lo carga en segundo plano para no bloquear el render.
     */
    @GetMapping("/splits/detect")
    @ResponseBody
    public List<DetectedSplit> detect() {
        return splitDetectionService.detect(currentUser.id());
    }

    /** Alta de un split sugerido: reutiliza el guardado normal, que recalcula el FIFO. */
    @PostMapping("/splits/detect/add")
    public String addDetected(@RequestParam String ticker,
                              @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                              @RequestParam BigDecimal ratio,
                              RedirectAttributes flash) {
        SplitForm form = new SplitForm();
        form.setTicker(ticker);
        form.setDate(date);
        form.setRatio(ratio);
        splitService.save(currentUser.id(), form);
        flash.addFlashAttribute("success",
                "Split de " + ticker + " añadido; el FIFO de ese valor se ha recalculado.");
        return "redirect:/splits";
    }

    @GetMapping("/splits/new")
    public String newForm(Model model) {
        model.addAttribute("form", new SplitForm());
        return "splits/form";
    }

    @PostMapping("/splits")
    public String save(@Valid @ModelAttribute("form") SplitForm form,
                       BindingResult result,
                       RedirectAttributes flash) {
        if (result.hasErrors()) return "splits/form";
        splitService.save(currentUser.id(), form);
        flash.addFlashAttribute("success", "Split registrado correctamente.");
        return "redirect:/splits";
    }

    @GetMapping("/splits/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        Split split = splitService.requireOwned(currentUser.id(), id);
        SplitForm form = new SplitForm();
        form.setDate(split.getDate());
        form.setTicker(split.getTicker());
        form.setRatio(split.getRatio());
        model.addAttribute("form", form);
        model.addAttribute("editId", id);
        return "splits/form";
    }

    @PostMapping("/splits/{id}/edit")
    public String update(@PathVariable Long id,
                         @Valid @ModelAttribute("form") SplitForm form,
                         BindingResult result,
                         Model model,
                         RedirectAttributes flash) {
        if (result.hasErrors()) {
            model.addAttribute("editId", id);
            return "splits/form";
        }
        splitService.update(currentUser.id(), id, form);
        flash.addFlashAttribute("success", "Split actualizado correctamente.");
        return "redirect:/splits";
    }

    @PostMapping("/splits/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes flash) {
        splitService.delete(currentUser.id(), id);
        flash.addFlashAttribute("success", "Split eliminado.");
        return "redirect:/splits";
    }
}
