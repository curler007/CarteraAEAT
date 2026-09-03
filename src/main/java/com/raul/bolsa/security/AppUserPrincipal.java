package com.raul.bolsa.security;

import com.raul.bolsa.domain.AppUser;
import com.raul.bolsa.domain.Role;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * Principal de Spring Security que además transporta el {@code id} del usuario.
 * Así {@link CurrentUser} resuelve el propietario de los datos sin consultar la BD
 * en cada petición.
 */
@Getter
public class AppUserPrincipal implements UserDetails {

    private final Long id;
    private final String username;
    private final String password;
    private final boolean enabled;
    private final Role role;
    /** Mientras esté a true, {@link MustChangePasswordFilter} no deja navegar a ningún sitio. */
    private final boolean mustChangePassword;

    public AppUserPrincipal(AppUser user) {
        this.id = user.getId();
        this.username = user.getUsername();
        this.password = user.getPasswordHash();
        this.enabled = user.isEnabled();
        this.role = user.getRole();
        this.mustChangePassword = user.isMustChangePassword();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }
}
