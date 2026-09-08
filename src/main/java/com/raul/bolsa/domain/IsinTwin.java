package com.raul.bolsa.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/**
 * Cómo se cotiza un ISIN en Yahoo Finance, y cómo le fue la última vez que se comprobó.
 *
 * <p>El ISIN de las operaciones no se toca nunca: es el oficial y es el que va a la AEAT. Cuando
 * Yahoo no lo reconoce, o lo reconoce solo en un mercado que no publica histórico, aquí se le pone
 * un <em>gemelo</em>: el símbolo de otro listado del mismo fondo. Para valorar da lo mismo —los
 * cuatro listados del Vanguard LifeStrategy 60 cotizan dentro de un 0,12 % entre sí— y así el dato
 * fiscal queda intacto.
 *
 * <p>La fila guarda además el resultado de la última comprobación, para no volver a preguntárselo
 * a Yahoo en cada visita al listado.
 */
@Entity
@Table(name = "isin_twins",
       uniqueConstraints = @UniqueConstraint(name = "uk_isin_twins_user_isin", columnNames = {"user_id", "isin"}),
       indexes = @Index(name = "idx_isin_twins_user_isin", columnList = "user_id, isin"))
@Setter
public class IsinTwin {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Propietario de la fila, igual que en el resto de tablas: nada cruza entre usuarios. */
    @Column(name = "user_id")
    private Long userId;

    /** ISIN oficial, tal y como está en las operaciones. */
    @Column(nullable = false)
    private String isin;

    /** Símbolo gemelo escrito a mano. Null mientras nadie lo haya puesto. */
    private String twin;

    /** Símbolo con el que se acabó cotizando: el gemelo si lo hay, o lo que resolvió Yahoo. */
    @Column(name = "resolved_symbol")
    private String resolvedSymbol;

    /**
     * Primer día con cierre publicado. Null significa que Yahoo no publica serie utilizable, que
     * es justo el caso que hay que arreglar poniendo un gemelo.
     */
    @Column(name = "history_from")
    private LocalDate historyFrom;

    /** Cuándo se comprobó por última vez. */
    @Column(name = "checked_at")
    private LocalDate checkedAt;

    /** Se da por bueno cuando Yahoo publica histórico: es lo que hace falta para los periodos. */
    public boolean isResolved() {
        return historyFrom != null;
    }
}
