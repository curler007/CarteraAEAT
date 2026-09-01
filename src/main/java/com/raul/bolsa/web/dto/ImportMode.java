package com.raul.bolsa.web.dto;

public enum ImportMode {
    /** Añade las filas del CSV a lo que el usuario ya tiene. */
    ADD,
    /** Borra operaciones, lotes, ventas y splits del usuario y los reconstruye desde el CSV. */
    REPLACE
}
