package com.raul.bolsa.web;

import com.raul.bolsa.domain.IsinTwin;
import com.raul.bolsa.web.dto.TwinCandidate;
import com.raul.bolsa.web.dto.TwinCheck;
import com.raul.bolsa.security.CurrentUser;
import com.raul.bolsa.service.IsinTwinService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;

@Controller
@RequiredArgsConstructor
public class IsinTwinController {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /**
     * A partir de aquí dejan de parecer el mismo fondo. Dos listados del mismo valor se separan
     * unas décimas por el desfase entre mercados; un 5 % ya es otra cosa.
     */
    private static final BigDecimal DRIFT_LIMIT = new BigDecimal("5");

    private final IsinTwinService twinService;
    private final CurrentUser currentUser;

    /**
     * Los que hay que arreglar primero. El orden es de trabajo, no alfabético: lo resuelto no pide
     * nada y solo estorba arriba, así que se va al fondo.
     */
    private static final Comparator<IsinTwin> PENDIENTES_PRIMERO =
            Comparator.comparing(IsinTwin::isResolved).thenComparing(IsinTwin::getIsin);

    /**
     * Lista de trabajo: solo lo que pide algo. Un ISIN que Yahoo resuelve solo y al que nadie ha
     * puesto gemelo no tiene nada que decidir aquí, y con 24 valores esconde a los cuatro que sí.
     * Los que llevan gemelo se quedan aunque estén en verde, porque son un apaño hecho a mano y
     * conviene poder verlo y deshacerlo.
     */
    @GetMapping("/gemelos")
    public String list(@RequestParam(defaultValue = "false") boolean todos, Model model) {
        List<IsinTwin> all = twinService.statuses(currentUser.id());
        List<IsinTwin> twins = all.stream()
                .filter(t -> todos || !t.isResolved() || t.getTwin() != null)
                .sorted(PENDIENTES_PRIMERO)
                .toList();
        model.addAttribute("twins", twins);
        model.addAttribute("todos", todos);
        model.addAttribute("pendientes", twins.stream().filter(t -> !t.isResolved()).count());
        model.addAttribute("ocultos", all.size() - twins.size());
        return "twins/list";
    }

    /** Listados que Yahoo ofrece para un ISIN, ya comprobados, para poder elegir uno. */
    @GetMapping("/api/gemelos/candidatos")
    @ResponseBody
    public List<TwinCandidate> candidates(@RequestParam String isin) {
        return twinService.candidates(isin);
    }

    @PostMapping("/gemelos")
    public String save(@RequestParam String isin,
                       @RequestParam(required = false) String twin,
                       RedirectAttributes flash) {
        TwinCheck check = twinService.setTwin(currentUser.id(), isin, twin);
        IsinTwin row = check.row();

        if (!row.isResolved()) {
            flash.addFlashAttribute("error", row.getTwin() != null
                    ? "Yahoo no publica histórico de " + row.getTwin()
                      + ". Prueba con otro listado del mismo fondo."
                    : "Sigue sin resolverse " + isin + ". Ponle un gemelo para poder cotizarlo.");
            return "redirect:/gemelos";
        }

        // Tener histórico no prueba que sea el fondo correcto, así que se enseña el precio para
        // que quien lo eligió pueda reconocerlo, y se compara con el del listado propio del ISIN
        // cuando lo hay. Un salto grande casi siempre es haberse equivocado de valor.
        StringBuilder msg = new StringBuilder(isin + " cotiza como " + row.getResolvedSymbol()
                + ", con histórico desde " + DATE_FMT.format(row.getHistoryFrom()) + ".");
        if (check.price() != null) {
            msg.append(" Ahora mismo vale ").append(check.price().stripTrailingZeros().toPlainString())
               .append(' ').append(check.currency()).append('.');
        }
        if (check.driftPct() != null) {
            msg.append(" El listado propio de ").append(isin).append(" marca ")
               .append(check.reference().stripTrailingZeros().toPlainString())
               .append(' ').append(check.referenceCurrency())
               .append(check.driftPct().compareTo(DRIFT_LIMIT) > 0
                       ? ": se separan un " + check.driftPct() + " %, revisa que sea el mismo fondo."
                       : ", un " + check.driftPct() + " % de diferencia: cuadra.");
        }
        flash.addFlashAttribute(
                check.driftPct() != null && check.driftPct().compareTo(DRIFT_LIMIT) > 0
                        ? "error" : "success",
                msg.toString());
        return "redirect:/gemelos";
    }
}
