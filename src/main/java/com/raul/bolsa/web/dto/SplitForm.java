package com.raul.bolsa.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Setter
public class SplitForm {

    @NotNull(message = "La fecha es obligatoria")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate date;

    @NotBlank(message = "El ticker es obligatorio")
    private String ticker;

    @NotNull(message = "El ratio es obligatorio")
    @DecimalMin(value = "0.0001", message = "El ratio debe ser mayor que 0")
    private BigDecimal ratio;
}
