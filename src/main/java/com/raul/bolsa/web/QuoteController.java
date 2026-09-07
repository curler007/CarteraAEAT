package com.raul.bolsa.web;

import com.raul.bolsa.security.CurrentUser;
import com.raul.bolsa.service.IsinTwinService;
import com.raul.bolsa.service.QuoteService;
import com.raul.bolsa.web.dto.QuoteResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class QuoteController {

    private final QuoteService quoteService;
    private final IsinTwinService twinService;
    private final CurrentUser currentUser;

    @GetMapping("/api/quote")
    public ResponseEntity<QuoteResult> quote(@RequestParam String isin) {
        String twin = twinService.twinOf(currentUser.id(), isin).orElse(null);
        return quoteService.getQuote(isin, twin)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
