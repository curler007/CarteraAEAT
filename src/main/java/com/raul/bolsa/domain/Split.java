package com.raul.bolsa.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "splits",
       indexes = @Index(name = "idx_splits_user_ticker", columnList = "user_id, ticker"))
@Getter
@Setter
public class Split {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Propietario de la fila. Nullable a nivel de DDL porque SQLite no admite
     * ALTER TABLE ADD COLUMN NOT NULL sin default sobre una tabla con datos;
     * en codigo se asigna siempre y LegacyDataMigration adopta las filas antiguas.
     */
    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false)
    private String ticker;

    /** LocalDateConverter se aplica automáticamente (autoApply = true) */
    @Column(nullable = false)
    private LocalDate date;

    /** Factor multiplicador: 10 para un split 1:10 */
    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal ratio;
}
