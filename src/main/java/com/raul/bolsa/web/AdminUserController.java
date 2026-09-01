package com.raul.bolsa.web;

import com.raul.bolsa.domain.AppUser;
import com.raul.bolsa.domain.Role;
import com.raul.bolsa.security.CurrentUser;
import com.raul.bolsa.service.AppUserService;
import com.raul.bolsa.web.dto.AppUserForm;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Gestión de usuarios. SecurityConfig restringe /admin/** al rol ADMIN.
 */
@Controller
@RequestMapping("/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final AppUserService userService;
    private final CurrentUser currentUser;

    @GetMapping
    public String list(Model model) {
        model.addAttribute("users", userService.findAll());
        model.addAttribute("currentUserId", currentUser.id());
        return "admin/users";
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("form", new AppUserForm());
        model.addAttribute("roles", Role.values());
        return "admin/form";
    }

    @PostMapping
    public String create(@Valid @ModelAttribute("form") AppUserForm form,
                         BindingResult result,
                         Model model,
                         RedirectAttributes flash) {
        if (form.getPassword() == null || form.getPassword().isBlank()) {
            result.rejectValue("password", "required", "La contraseña es obligatoria");
        }
        if (result.hasErrors()) {
            model.addAttribute("roles", Role.values());
            return "admin/form";
        }
        try {
            userService.create(form);
            flash.addFlashAttribute("success", "Usuario creado correctamente.");
        } catch (IllegalStateException e) {
            model.addAttribute("error", e.getMessage());
            model.addAttribute("roles", Role.values());
            return "admin/form";
        }
        return "redirect:/admin/users";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        AppUser user = userService.require(id);
        AppUserForm form = new AppUserForm();
        form.setUsername(user.getUsername());
        form.setRole(user.getRole());
        form.setEnabled(user.isEnabled());
        model.addAttribute("form", form);
        model.addAttribute("editId", id);
        model.addAttribute("roles", Role.values());
        return "admin/form";
    }

    @PostMapping("/{id}/edit")
    public String update(@PathVariable Long id,
                         @Valid @ModelAttribute("form") AppUserForm form,
                         BindingResult result,
                         Model model,
                         RedirectAttributes flash) {
        if (result.hasErrors()) {
            model.addAttribute("editId", id);
            model.addAttribute("roles", Role.values());
            return "admin/form";
        }
        try {
            userService.update(id, form, currentUser.id());
            flash.addFlashAttribute("success", "Usuario actualizado correctamente.");
        } catch (IllegalStateException e) {
            model.addAttribute("editId", id);
            model.addAttribute("error", e.getMessage());
            model.addAttribute("roles", Role.values());
            return "admin/form";
        }
        return "redirect:/admin/users";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes flash) {
        try {
            userService.delete(id, currentUser.id());
            flash.addFlashAttribute("success", "Usuario eliminado junto con todos sus datos.");
        } catch (IllegalStateException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/users";
    }
}
