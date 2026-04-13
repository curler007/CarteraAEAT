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
@Table(name = "fifo_lots")
@Getter
@Setter
public class FifoLot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

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
