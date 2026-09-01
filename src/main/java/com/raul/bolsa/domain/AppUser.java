package com.raul.bolsa.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/**
 * Usuario de la aplicación. Cada usuario tiene su propia cartera: operaciones, lotes,
 * ventas y splits se filtran siempre por {@code userId}.
 */
@Entity
@Table(name = "app_users")
@Getter
@Setter
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    /** Hash BCrypt, nunca la contraseña en claro. */
    @Column(nullable = false)
    private String passwordHash;

    @Column(nullable = false)
    private boolean enabled = true;

    /** columnDefinition = "TEXT" evita el CHECK constraint que impediría añadir roles después. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "TEXT")
    private Role role = Role.USER;

    @Column(nullable = false)
    private LocalDate createdAt = LocalDate.now();

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }
}
