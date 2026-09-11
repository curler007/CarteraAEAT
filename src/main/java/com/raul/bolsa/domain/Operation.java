package com.raul.bolsa.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

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
     * Empareja las dos patas de un traspaso entre fondos. Todas las salidas y entradas de un
     * mismo traspaso comparten valor, porque el reparto es de muchos fondos a muchos fondos:
     * el coste que sale del conjunto de origen es el que entra en el conjunto de destino, y solo
     * tiene sentido repartirlo mirando el evento entero.
     */
    @Column(name = "transfer_id")
    private String transferId;

    /**
     * Identidad estable de la operación, la misma en el CSV que en la base de datos.
     *
     * <p>Existe para que reimportar una exportación propia no duplique nada. El {@code id} no
     * sirve para eso: es un autonumérico que cada base reparte a su manera, así que el mismo
     * hecho económico tendría un id distinto en cada instalación y ninguno al salir en el CSV.
     *
     * <p>Se asigna al insertar y no se vuelve a tocar. Sobrevive a una edición —{@code update}
     * borra la fila y la reinserta, pero el formulario arrastra el uid— porque lo contrario
     * convertiría cada corrección en una operación nueva a los ojos del importador.
     *
     * <p>Nullable en el DDL por lo mismo que {@code user_id}: SQLite no admite añadir una columna
     * NOT NULL a una tabla con datos. Las filas anteriores las rellena LegacyDataMigration.
     */
    @Column(name = "uid", length = 36)
    private String uid;

    @PrePersist
    void assignUid() {
        if (uid == null || uid.isBlank()) {
            uid = UUID.randomUUID().toString();
        }
    }

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

    /**
     * Entró en la cartera sin que sepamos lo que costó: normalmente un traspaso recibido de otro
     * broker. Mientras siga a cero, venderla computaría toda la venta como ganancia.
     *
     * <p>Un CANJE no cuenta: sus acciones son liberadas y su coste cero es el correcto
     * (LIRPF Art. 37.1.a), no un dato que falte.
     */
    public boolean isUnvalued() {
        return type != OperationType.CANJE && total != null && total.signum() == 0;
    }
}
