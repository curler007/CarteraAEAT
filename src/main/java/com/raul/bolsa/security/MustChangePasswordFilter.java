package com.raul.bolsa.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * Retiene al usuario en el formulario de cambio de contraseña mientras tenga el cambio
 * pendiente.
 *
 * <p>No basta con redirigir al iniciar sesión: sin este filtro bastaría con teclear
 * {@code /dashboard} en la barra de direcciones para saltarse el cambio. Al ir después
 * del filtro de autorización, cubre todos los endpoints, incluidos el CSV de ventas y
 * los JSON de autocompletado.
 */
public class MustChangePasswordFilter extends OncePerRequestFilter {

    public static final String CHANGE_PATH = "/password/change";

    /** Lo mínimo para poder cambiar la contraseña o marcharse. */
    private static final Set<String> ALLOWED = Set.of(CHANGE_PATH, "/login", "/logout", "/error");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null
                && auth.getPrincipal() instanceof AppUserPrincipal principal
                && principal.isMustChangePassword()
                && !ALLOWED.contains(pathWithinApplication(request))) {
            response.sendRedirect(request.getContextPath() + CHANGE_PATH);
            return;
        }
        chain.doFilter(request, response);
    }

    private String pathWithinApplication(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }
}
