package com.raul.bolsa.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PasswordChangeForm {

    /** Solo se pide en el cambio voluntario; en el forzado se ignora. */
    private String currentPassword;

    @NotBlank(message = "La nueva contraseña es obligatoria")
    @Size(min = 8, max = 100, message = "Mínimo 8 caracteres")
    private String password;

    @NotBlank(message = "Repite la nueva contraseña")
    private String confirmPassword;

    public boolean isConfirmed() {
        return password != null && password.equals(confirmPassword);
    }
}
