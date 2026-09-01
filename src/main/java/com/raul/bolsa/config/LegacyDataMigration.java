package com.raul.bolsa.config;

import com.raul.bolsa.domain.AppUser;
import com.raul.bolsa.domain.Role;
import com.raul.bolsa.repository.AppUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Convierte una instalación monousuario en multiusuario sin perder datos.
 *
 * <p>Es idempotente: en un arranque normal no encuentra nada que hacer y no toca la BD.
 * Solo actúa la primera vez, o si alguna fila quedase huérfana.
 *
 * <ol>
 *   <li>Si no hay ningún usuario, crea el administrador inicial con las credenciales de
 *       {@code app.security.username} / {@code app.security.password}.</li>
 *   <li>Asigna a ese administrador todas las filas que aún no tienen propietario.</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LegacyDataMigration implements ApplicationRunner {

    private static final List<String> OWNED_TABLES =
            List.of("operations", "fifo_lots", "sale_records", "splits");

    private final AppUserRepository userRepo;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbc;

    @Value("${app.security.username}")
    private String seedUsername;

    @Value("${app.security.password}")
    private String seedPassword;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        AppUser admin = ensureInitialAdmin();
        adoptOrphanRows(admin);
    }

    private AppUser ensureInitialAdmin() {
        if (userRepo.count() > 0) {
            return userRepo.findByUsername(seedUsername).orElseGet(this::anyAdmin);
        }
        AppUser admin = new AppUser();
        admin.setUsername(seedUsername);
        admin.setPasswordHash(passwordEncoder.encode(seedPassword));
        admin.setRole(Role.ADMIN);
        admin.setEnabled(true);
        admin = userRepo.save(admin);
        log.info("Creado el administrador inicial '{}' a partir de application.properties", seedUsername);
        return admin;
    }

    private AppUser anyAdmin() {
        return userRepo.findAll().stream()
                .filter(AppUser::isAdmin)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Hay usuarios pero ninguno es administrador; no puedo adoptar las filas huérfanas"));
    }

    /**
     * Las filas creadas antes de existir los usuarios tienen user_id NULL.
     * Pasan a pertenecer al administrador inicial, que es quien las creó.
     */
    private void adoptOrphanRows(AppUser owner) {
        int total = 0;
        for (String table : OWNED_TABLES) {
            int updated = jdbc.update(
                    "UPDATE " + table + " SET user_id = ? WHERE user_id IS NULL", owner.getId());
            if (updated > 0) {
                log.info("Migración multiusuario: {} filas de '{}' asignadas a '{}'",
                        updated, table, owner.getUsername());
            }
            total += updated;
        }
        if (total > 0) {
            log.info("Migración multiusuario completada: {} filas adoptadas en total", total);
        }
    }
}
