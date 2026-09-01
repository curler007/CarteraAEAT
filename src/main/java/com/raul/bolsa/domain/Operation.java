package com.raul.bolsa.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "operations",
       indexes = @Index(name = "idx_operations_user_ticker", columnList = "user_id, ticker"))
@Getter
@Setter
public class Operation {

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
    private LocalDate date;

    @Column(nullable = false)
    private String broker;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "TEXT")
    private OperationType type;

    @Column(nullable = false)
    private String ticker;

    @Column(nullable = false)
    private String assetName;

    /** Admite fracciones de acciones */
    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal quantity;

    @Column(nullable = false, precision = 20, scale = 6)
    private BigDecimal price;

    @Column(nullable = false, precision = 20, scale = 6)
    private BigDecimal commission;

    /**
     * Coste total real:
     *   BUY  → (quantity * price) + commission
     *   SELL → (quantity * price) - commission
     */
    @Column(nullable = false, precision = 20, scale = 6)
    private BigDecimal total;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AeatGroup aeatGroup;

    private String notes;

    /**
     * Para ventas: cantidad de acciones aún sin casar con ningún lote de compra.
     * null o 0 = totalmente resuelta. > 0 = pendiente (parcial o total).
     * Siempre null para compras.
     */
    @Column(precision = 20, scale = 8)
    private BigDecimal pendingQty;

    /** Usado en la vista para evitar lógica en el template. */
    public boolean isPending() {
        return pendingQty != null && pendingQty.compareTo(BigDecimal.ZERO) > 0;
    }
}
