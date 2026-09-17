package com.raul.bolsa.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Un tipo de cambio oficial del BCE: las unidades de {@code currency} que compra un euro un día.
 *
 * <p>La serie vive en la base de datos y no solo en memoria por dos motivos que se notan en el
 * NAS. El primero es que sobrevive al reinicio: sin tabla, cada arranque vuelve a descargar los
 * veintisiete años de serie. El segundo es el que importa de verdad: cuando el BCE no responde se
 * sigue convirtiendo con el último día guardado, en vez de dejar los precios sin convertir.
 *
 * <p>No es caché de la que se pueda prescindir: un tipo de cambio publicado no cambia nunca, así
 * que una fila guardada es un dato definitivo y no una copia que pueda quedar obsoleta.
 */
@Entity
@Table(name = "fx_rates",
       uniqueConstraints = @UniqueConstraint(name = "uk_fx_rates_currency_date",
                                             columnNames = {"currency", "rate_date"}),
       indexes = @Index(name = "idx_fx_rates_currency", columnList = "currency"))
@Getter
@Setter
@NoArgsConstructor
public class FxRate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Código ISO en mayúsculas: USD, GBP, CAD… */
    @Column(nullable = false, length = 3)
    private String currency;

    /** LocalDateConverter se aplica automáticamente (autoApply = true). */
    @Column(name = "rate_date", nullable = false)
    private LocalDate rateDate;

    /** Unidades de la divisa que compra un euro ese día. */
    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal rate;

    public FxRate(String currency, LocalDate rateDate, BigDecimal rate) {
        this.currency = currency;
        this.rateDate = rateDate;
        this.rate = rate;
    }
}
