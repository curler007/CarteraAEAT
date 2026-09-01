package com.raul.bolsa.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Representa un lote de compra pendiente de vender.
 * El FIFO se aplica globalmente por ticker (no por broker), que es lo que exige Hacienda.
 */
@Entity
@Table(name = "fifo_lots",
       indexes = @Index(name = "idx_fifo_lots_user_ticker", columnList = "user_id, ticker"))
@Getter
@Setter
public class FifoLot {

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

    @ManyToOne(optional = false)
    @JoinColumn(name = "operation_id")
    private Operation operation;

    @Column(nullable = false)
    private String ticker;

    @Column(nullable = false)
    private String assetName;

    @Column(nullable = false)
    private LocalDate purchaseDate;

    @Column(nullable = false)
    private String broker;

    /** Cantidad original del lote */
    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal initialQty;

    /** Cantidad que queda por vender */
    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal remainingQty;

    /** Coste total original (con comisión incluida) */
    @Column(nullable = false, precision = 20, scale = 6)
    private BigDecimal initialCost;

    /** Coste proporcional que queda por imputar */
    @Column(nullable = false, precision = 20, scale = 6)
    private BigDecimal remainingCost;
}
