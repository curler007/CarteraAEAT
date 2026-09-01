package com.raul.bolsa;

import com.raul.bolsa.domain.AppUser;
import com.raul.bolsa.domain.Role;
import com.raul.bolsa.repository.AppUserRepository;

/** Alta de usuarios para los tests, sin pasar por la capa web. */
final class TestUsers {

    private TestUsers() {
    }

    static AppUser create(AppUserRepository repo, String username) {
        return repo.findByUsername(username).orElseGet(() -> {
            AppUser u = new AppUser();
            u.setUsername(username);
            u.setPasswordHash("{noop}irrelevante-en-tests");
            u.setRole(Role.USER);
            u.setEnabled(true);
            return repo.save(u);
        });
    }
}
