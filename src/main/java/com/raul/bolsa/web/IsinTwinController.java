package com.raul.bolsa.web;

import com.raul.bolsa.security.CurrentUser;
import com.raul.bolsa.service.IsinTwinService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.format.DateTimeFormatter;

@Controller
@RequiredArgsConstructor
public class IsinTwinController {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final IsinTwinService twinService;
    private final CurrentUser currentUser;

    @GetMapping("/gemelos")
    public String list(Model model) {
        model.addAttribute("twins", twinService.statuses(currentUser.id()));
        return "twins/list";
    }

    @PostMapping("/gemelos")
    public String save(@RequestParam String isin,
                       @RequestParam(required = false) String twin,
                       RedirectAttributes flash) {
        var saved = twinService.setTwin(currentUser.id(), isin, twin);
        if (saved.isResolved()) {
            flash.addFlashAttribute("success", isin + " ya cotiza como " + saved.getResolvedSymbol()
                    + ", con histórico desde " + DATE_FMT.format(saved.getHistoryFrom()) + ".");
        } else if (saved.getTwin() != null) {
            flash.addFlashAttribute("error", "Yahoo no publica histórico de " + saved.getTwin()
                    + ". Prueba con otro listado del mismo fondo.");
        } else {
            flash.addFlashAttribute("error", "Sigue sin resolverse " + isin
                    + ". Ponle un gemelo para poder cotizarlo.");
        }
        return "redirect:/gemelos";
    }
}
