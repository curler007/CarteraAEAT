package com.raul.bolsa.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "splits")
@Getter
@Setter
public class Split {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String ticker;

    /** LocalDateConverter se aplica automáticamente (autoApply = true) */
    @Column(nullable = false)
    private LocalDate date;

    /** Factor multiplicador: 10 para un split 1:10 */
    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal ratio;
}
