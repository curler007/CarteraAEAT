package com.raul.bolsa;

import com.raul.bolsa.domain.AppUser;
import com.raul.bolsa.domain.Role;
import com.raul.bolsa.repository.AppUserRepository;
import com.raul.bolsa.service.AppUserService;
import com.raul.bolsa.web.dto.AppUserForm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * El usuario al que un administrador le pone la contraseña no puede usar la aplicación
 * hasta cambiarla.
 *
 * <p>La prueba navega a {@code /dashboard} <em>directamente</em>, sin seguir la redirección
 * del login: si el cambio se forzara solo al iniciar sesión, bastaría con teclear la URL
 * para saltárselo y este test no lo detectaría.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ForcedPasswordChangeTest {

    private static final String PROVISIONAL = "provisional-123";
    private static final String ELEGIDA = "la-mia-secreta";

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) throws IOException {
        Path db = Files.createTempDirectory("bolsa-password-").resolve("test.db");
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + db.toAbsolutePath());
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
        registry.add("app.security.username", () -> "test");
        registry.add("app.security.password", () -> "test");
    }

    @Autowired MockMvc mvc;
    @Autowired AppUserRepository userRepo;
    @Autowired AppUserService userService;

    @BeforeEach
    void setUp() {
        userRepo.findByUsername("nuevo").ifPresent(userRepo::delete);

        AppUserForm form = new AppUserForm();
        form.setUsername("nuevo");
        form.setPassword(PROVISIONAL);
        form.setRole(Role.USER);
        form.setEnabled(true);
        userService.create(form);
    }

    @Test
    @DisplayName("El alta por un administrador deja el cambio de contraseña pendiente")
    void altaMarcaElCambioComoPendiente() {
        assertTrue(userRepo.findByUsername("nuevo").orElseThrow().isMustChangePassword());
    }

    @Test
    @DisplayName("Con el cambio pendiente, cualquier página redirige al formulario")
    void navegacionBloqueadaHastaCambiar() throws Exception {
        MockHttpSession session = login();

        mvc.perform(get("/dashboard").session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/password/change"));
        mvc.perform(get("/operations").session(session))
                .andExpect(redirectedUrl("/password/change"));
        mvc.perform(get("/sales/export.csv").session(session))
                .andExpect(redirectedUrl("/password/change"));

        // El propio formulario sí es accesible, o no habría forma de salir del bloqueo.
        mvc.perform(get("/password/change").session(session))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Tras cambiarla, la sesión en curso ya navega con normalidad")
    void trasCambiarlaSeNavegaConNormalidad() throws Exception {
        MockHttpSession session = login();

        mvc.perform(post("/password/change").session(session)
                        .with(csrf())
                        .param("password", ELEGIDA)
                        .param("confirmPassword", ELEGIDA))
                .andExpect(redirectedUrl("/dashboard"));

        assertFalse(userRepo.findByUsername("nuevo").orElseThrow().isMustChangePassword());

        // Sin renovar el principal en la sesión, esto seguiría redirigiendo al formulario.
        mvc.perform(get("/dashboard").session(session))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("La nueva contraseña no puede ser la provisional ni distinta de su confirmación")
    void rechazaContrasenasInvalidas() throws Exception {
        MockHttpSession session = login();

        mvc.perform(post("/password/change").session(session)
                        .with(csrf())
                        .param("password", ELEGIDA)
                        .param("confirmPassword", "otra-cosa"))
                .andExpect(status().isOk());

        mvc.perform(post("/password/change").session(session)
                        .with(csrf())
                        .param("password", PROVISIONAL)
                        .param("confirmPassword", PROVISIONAL))
                .andExpect(status().isOk());

        AppUser user = userRepo.findByUsername("nuevo").orElseThrow();
        assertTrue(user.isMustChangePassword(), "el cambio debe seguir pendiente");
    }

    /** Login real por formulario: la sesión resultante lleva el principal con el flag. */
    private MockHttpSession login() throws Exception {
        MvcResult result = mvc.perform(formLogin("/login").user("nuevo").password(PROVISIONAL))
                .andExpect(redirectedUrl("/dashboard"))
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }
}
