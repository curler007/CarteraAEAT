package com.raul.bolsa.web.dto;

import com.raul.bolsa.domain.Role;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AppUserForm {

    @NotBlank(message = "El usuario es obligatorio")
    @Size(min = 3, max = 40, message = "Entre 3 y 40 caracteres")
    @Pattern(regexp = "[a-zA-Z0-9._-]+",
             message = "Solo letras, números, punto, guion y guion bajo")
    private String username;

    /** En alta es obligatoria; en edición, vacía significa «no cambiar». */
    @Size(max = 100)
    private String password;

    @NotNull
    private Role role = Role.USER;

    private boolean enabled = true;

    /** Por defecto la contraseña que escribe el administrador es provisional. */
    private boolean mustChangePassword = true;
}
