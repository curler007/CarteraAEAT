package com.raul.bolsa.web;

import com.raul.bolsa.service.QuoteService;
import com.raul.bolsa.web.dto.QuoteResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class QuoteController {

    private final QuoteService quoteService;

    @GetMapping("/api/quote")
    public ResponseEntity<QuoteResult> quote(@RequestParam String isin) {
        return quoteService.getQuote(isin)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
