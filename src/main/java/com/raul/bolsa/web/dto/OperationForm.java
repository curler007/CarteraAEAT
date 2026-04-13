package com.raul.bolsa.web.dto;

import com.raul.bolsa.domain.AeatGroup;
import com.raul.bolsa.domain.OperationType;
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
public class OperationForm {

    @NotNull
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate date = LocalDate.now();

    @NotBlank
    private String broker;

    @NotNull
    private OperationType type = OperationType.BUY;

    @NotBlank
    private String ticker;

    @NotBlank
    private String assetName;

    @NotNull
    @DecimalMin(value = "0.00000001", message = "La cantidad debe ser mayor que 0")
    private BigDecimal quantity;

    // Null permitido para CANJE (acciones liberadas, coste = 0)
    private BigDecimal total;

    @NotNull
    private BigDecimal commission = BigDecimal.ZERO;

    @NotNull
    private AeatGroup aeatGroup = AeatGroup.GROUP_2;

    private String notes;
}
