package com.raul.bolsa.web;

import com.raul.bolsa.domain.Split;
import com.raul.bolsa.repository.SplitRepository;
import com.raul.bolsa.service.SplitService;
import com.raul.bolsa.web.dto.SplitForm;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequiredArgsConstructor
public class SplitController {

    private final SplitRepository splitRepo;
    private final SplitService splitService;

    @GetMapping("/splits")
    public String list(Model model) {
        model.addAttribute("splits",
                splitRepo.findAll(Sort.by(Sort.Direction.DESC, "date", "id")));
        return "splits/list";
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
        splitService.save(form);
        flash.addFlashAttribute("success", "Split registrado correctamente.");
        return "redirect:/splits";
    }

    @GetMapping("/splits/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        Split split = splitRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Split no encontrado: " + id));
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
        splitService.update(id, form);
        flash.addFlashAttribute("success", "Split actualizado correctamente.");
        return "redirect:/splits";
    }

    @PostMapping("/splits/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes flash) {
        splitService.delete(id);
        flash.addFlashAttribute("success", "Split eliminado.");
        return "redirect:/splits";
    }
}
