package com.raul.bolsa.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Un registro por cada lote consumido en una venta.
 * Si una venta consume 2 lotes con fechas distintas → 2 SaleRecords.
 * Esto es necesario para la AEAT, que pide la fecha de adquisición de cada lote.
 */
@Entity
@Table(name = "sale_records")
@Getter
@Setter
public class SaleRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "sell_operation_id")
    private Operation sellOperation;

    @ManyToOne(optional = false)
    @JoinColumn(name = "fifo_lot_id")
    private FifoLot consumedLot;

    @Column(nullable = false)
    private String ticker;

    @Column(nullable = false)
    private String assetName;

    /** Fecha de adquisición del lote consumido (para AEAT) */
    @Column(nullable = false)
    private LocalDate purchaseDate;

    /** Broker donde se compró el lote */
    @Column(nullable = false)
    private String buyBroker;

    @Column(nullable = false)
    private LocalDate saleDate;

    /** Broker donde se ejecutó la venta */
    @Column(nullable = false)
    private String sellBroker;

    /** Cantidad vendida de este lote */
    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal quantity;

    /** Coste de adquisición (con comisión, FIFO proporcional) → campo "Valor adquisición" AEAT */
    @Column(nullable = false, precision = 20, scale = 6)
    private BigDecimal costBasis;

    /** Ingreso neto de comisión (proporcional) → campo "Valor transmisión" AEAT */
    @Column(nullable = false, precision = 20, scale = 6)
    private BigDecimal proceeds;

    /** proceeds - costBasis */
    @Column(nullable = false, precision = 20, scale = 6)
    private BigDecimal gainLoss;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AeatGroup aeatGroup;

    private int taxYear;
}
