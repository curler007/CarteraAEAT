package com.raul.bolsa.web;

import com.raul.bolsa.domain.IsinTwin;
import com.raul.bolsa.security.CurrentUser;
import com.raul.bolsa.service.IsinTwinService;
import com.raul.bolsa.web.dto.IsinStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class IsinStatusController {

    private final IsinTwinService twinService;
    private final CurrentUser currentUser;

    /**
     * Estado de cada ISIN de la cartera para pintar el listado de operaciones. Lo ya comprobado
     * sale de la base sin volver a molestar a Yahoo.
     */
    @GetMapping("/api/isin-status")
    public List<IsinStatus> statuses() {
        return twinService.statuses(currentUser.id()).stream()
                .map(IsinStatusController::toDto)
                .toList();
    }

    private static IsinStatus toDto(IsinTwin row) {
        return new IsinStatus(
                row.getIsin(),
                row.getTwin(),
                row.isResolved(),
                row.getResolvedSymbol(),
                row.getHistoryFrom() == null ? null : row.getHistoryFrom().toString());
    }
}
