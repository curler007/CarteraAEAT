package com.raul.bolsa.service;

import com.raul.bolsa.domain.AppUser;
import com.raul.bolsa.domain.Role;
import com.raul.bolsa.repository.AppUserRepository;
import com.raul.bolsa.repository.FifoLotRepository;
import com.raul.bolsa.repository.OperationRepository;
import com.raul.bolsa.repository.SaleRecordRepository;
import com.raul.bolsa.repository.SplitRepository;
import com.raul.bolsa.web.dto.AppUserForm;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Alta y mantenimiento de usuarios. Solo accesible para administradores.
 *
 * <p>Protege dos invariantes: siempre debe quedar al menos un administrador activo,
 * y nadie puede eliminarse ni desactivarse a sí mismo.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AppUserService {

    private final AppUserRepository userRepo;
    private final OperationRepository operationRepo;
    private final FifoLotRepository fifoLotRepo;
    private final SaleRecordRepository saleRecordRepo;
    private final SplitRepository splitRepo;
    private final PasswordEncoder passwordEncoder;

    public List<AppUser> findAll() {
        return userRepo.findAllByOrderByUsernameAsc();
    }

    public AppUser require(Long id) {
        return userRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Usuario no encontrado: " + id));
    }

    @Transactional
    public AppUser create(AppUserForm form) {
        String username = form.getUsername().trim();
        if (userRepo.existsByUsername(username)) {
            throw new IllegalStateException("Ya existe un usuario con el nombre '" + username + "'.");
        }
        if (form.getPassword() == null || form.getPassword().isBlank()) {
            throw new IllegalStateException("La contraseña es obligatoria al crear un usuario.");
        }
        AppUser user = new AppUser();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(form.getPassword()));
        user.setRole(form.getRole());
        user.setEnabled(form.isEnabled());
        user.setMustChangePassword(form.isMustChangePassword());
        user = userRepo.save(user);
        log.info("Usuario '{}' creado con rol {}", username, form.getRole());
        return user;
    }

    /** Una contraseña vacía significa «dejar la que tenía». */
    @Transactional
    public AppUser update(Long id, AppUserForm form, Long actingUserId) {
        AppUser user = require(id);
        String username = form.getUsername().trim();

        if (!user.getUsername().equals(username) && userRepo.existsByUsername(username)) {
            throw new IllegalStateException("Ya existe un usuario con el nombre '" + username + "'.");
        }

        boolean losesAdmin = user.isAdmin()
                && (form.getRole() != Role.ADMIN || !form.isEnabled());
        if (losesAdmin && isLastActiveAdmin(user)) {
            throw new IllegalStateException(
                    "No puedes dejar la aplicación sin ningún administrador activo.");
        }
        if (id.equals(actingUserId) && !form.isEnabled()) {
            throw new IllegalStateException("No puedes desactivar tu propio usuario.");
        }

        user.setUsername(username);
        user.setRole(form.getRole());
        user.setEnabled(form.isEnabled());
        // El flag solo se toca al restablecer la contraseña: si el admin no escribe una nueva,
        // el usuario conserva la suya y no hay nada que obligarle a cambiar.
        if (form.getPassword() != null && !form.getPassword().isBlank()) {
            user.setPasswordHash(passwordEncoder.encode(form.getPassword()));
            user.setMustChangePassword(form.isMustChangePassword());
            log.info("Contraseña restablecida para '{}'", username);
        }
        return userRepo.save(user);
    }

    /**
     * Cambio de contraseña por el propio usuario, que es la única vía para levantar el
     * {@code mustChangePassword}.
     */
    @Transactional
    public AppUser changeOwnPassword(Long userId, String currentPassword, String newPassword) {
        AppUser user = require(userId);

        // En el cambio voluntario exigimos la contraseña actual, para que una sesión abierta
        // y desatendida no baste para apropiarse de la cuenta. En el cambio forzado no aporta
        // nada: el usuario acaba de teclearla en el login.
        if (!user.isMustChangePassword()
                && !passwordEncoder.matches(
                        currentPassword == null ? "" : currentPassword, user.getPasswordHash())) {
            throw new IllegalStateException("La contraseña actual no es correcta.");
        }
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new IllegalStateException("La nueva contraseña debe ser distinta de la actual.");
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(false);
        log.info("El usuario '{}' ha cambiado su contraseña", user.getUsername());
        return userRepo.save(user);
    }

    /**
     * Elimina el usuario y toda su cartera. Es irreversible, de ahí la confirmación
     * explícita en la interfaz.
     */
    @Transactional
    public void delete(Long id, Long actingUserId) {
        AppUser user = require(id);
        if (id.equals(actingUserId)) {
            throw new IllegalStateException("No puedes eliminar tu propio usuario.");
        }
        if (user.isAdmin() && isLastActiveAdmin(user)) {
            throw new IllegalStateException("No puedes eliminar al último administrador activo.");
        }

        // El orden importa: las ventas referencian lotes y operaciones.
        saleRecordRepo.deleteByUserId(id);
        fifoLotRepo.deleteByUserId(id);
        operationRepo.deleteByUserId(id);
        splitRepo.deleteByUserId(id);
        userRepo.delete(user);
        log.info("Usuario '{}' eliminado junto con todos sus datos", user.getUsername());
    }

    private boolean isLastActiveAdmin(AppUser user) {
        return user.isEnabled() && userRepo.countByRoleAndEnabledTrue(Role.ADMIN) <= 1;
    }
}
