package com.raul.bolsa.repository;

import com.raul.bolsa.domain.AppUser;
import com.raul.bolsa.domain.Role;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByUsername(String username);

    boolean existsByUsername(String username);

    List<AppUser> findAllByOrderByUsernameAsc();

    /** Para impedir que se elimine o degrade al último administrador. */
    long countByRoleAndEnabledTrue(Role role);
}
