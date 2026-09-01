package com.raul.bolsa.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Resuelve el usuario autenticado en la petición en curso.
 * Todo el filtrado de datos por propietario parte de aquí.
 */
@Component
public class CurrentUser {

    public AppUserPrincipal get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AppUserPrincipal principal)) {
            throw new IllegalStateException("No hay un usuario autenticado en el contexto");
        }
        return principal;
    }

    /** Id del usuario autenticado, que es el propietario de los datos a leer o escribir. */
    public Long id() {
        return get().getId();
    }

    public String username() {
        return get().getUsername();
    }

    public boolean isAdmin() {
        return get().getRole() == com.raul.bolsa.domain.Role.ADMIN;
    }
}
