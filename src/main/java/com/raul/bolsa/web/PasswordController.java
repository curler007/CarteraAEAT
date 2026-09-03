package com.raul.bolsa.web;

import com.raul.bolsa.domain.AppUser;
import com.raul.bolsa.security.AppUserPrincipal;
import com.raul.bolsa.security.CurrentUser;
import com.raul.bolsa.service.AppUserService;
import com.raul.bolsa.web.dto.PasswordChangeForm;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Cambio de la propia contraseña. Sirve a dos casos con el mismo formulario: el cambio
 * forzado del primer acceso —al que {@code MustChangePasswordFilter} redirige todo— y el
 * cambio voluntario desde el menú.
 */
@Controller
@RequestMapping("/password")
@RequiredArgsConstructor
public class PasswordController {

    private final AppUserService userService;
    private final CurrentUser currentUser;
    private final SecurityContextRepository securityContextRepository;

    @GetMapping("/change")
    public String form(Model model) {
        model.addAttribute("form", new PasswordChangeForm());
        model.addAttribute("forced", currentUser.get().isMustChangePassword());
        return "password/change";
    }

    @PostMapping("/change")
    public String change(@Valid @ModelAttribute("form") PasswordChangeForm form,
                         BindingResult result,
                         Model model,
                         HttpServletRequest request,
                         HttpServletResponse response,
                         RedirectAttributes flash) {
        boolean forced = currentUser.get().isMustChangePassword();
        if (!form.isConfirmed()) {
            result.rejectValue("confirmPassword", "mismatch", "Las contraseñas no coinciden");
        }
        if (result.hasErrors()) {
            model.addAttribute("forced", forced);
            return "password/change";
        }

        AppUser updated;
        try {
            updated = userService.changeOwnPassword(
                    currentUser.id(), form.getCurrentPassword(), form.getPassword());
        } catch (IllegalStateException e) {
            model.addAttribute("forced", forced);
            model.addAttribute("error", e.getMessage());
            return "password/change";
        }

        refreshAuthentication(updated, request, response);
        flash.addFlashAttribute("success", "Contraseña actualizada correctamente.");
        return "redirect:/dashboard";
    }

    /**
     * Renueva el principal de la sesión. Sin esto el filtro seguiría viendo el flag
     * antiguo y redirigiría en bucle al formulario. De paso se renueva el id de sesión,
     * porque las credenciales han cambiado.
     */
    private void refreshAuthentication(AppUser user,
                                       HttpServletRequest request,
                                       HttpServletResponse response) {
        request.changeSessionId();
        AppUserPrincipal principal = new AppUserPrincipal(user);
        Authentication auth = new UsernamePasswordAuthenticationToken(
                principal, principal.getPassword(), principal.getAuthorities());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
    }
}
