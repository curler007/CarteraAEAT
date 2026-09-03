package com.raul.bolsa.service;

import com.raul.bolsa.domain.Split;
import com.raul.bolsa.repository.SplitRepository;
import com.raul.bolsa.web.dto.SplitForm;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SplitService {

    private final SplitRepository splitRepo;
    private final FifoService fifoService;

    /**
     * Factor multiplicador acumulado de los splits en la lista que ocurrieron
     * estrictamente después de {@code afterDate} y hasta {@code upTo} (inclusive).
     * <p>
     * Ejemplo: un split ×10 el día 2 y otro ×2 el día 5, con afterDate=día 1 y upTo=hoy
     * → devuelve 20.
     * <p>
     * Úsalo para convertir una cantidad en términos de la fecha de compra a términos actuales:
     * {@code qty * cumulativeFactor(splits, purchaseDate, today)}
     */
    public BigDecimal cumulativeFactor(List<Split> splits, LocalDate afterDate, LocalDate upTo) {
        return splits.stream()
                .filter(s -> s.getDate().isAfter(afterDate) && !s.getDate().isAfter(upTo))
                .map(Split::getRatio)
                .reduce(BigDecimal.ONE, BigDecimal::multiply);
    }

    /** Sobrecarga que carga los splits del ticker para ese usuario desde la BD. */
    public BigDecimal cumulativeFactor(Long userId, String ticker, LocalDate afterDate, LocalDate upTo) {
        return cumulativeFactor(
                splitRepo.findByUserIdAndTickerOrderByDateAscIdAsc(userId, ticker.toUpperCase()),
                afterDate, upTo);
    }

    @Transactional
    public Split save(Long userId, SplitForm form) {
        Split split = saveWithoutRecalc(userId, form);
        fifoService.recalculateFifo(userId, split.getTicker());
        return split;
    }

    /**
     * Registra el split sin tocar el FIFO. Solo para cargas masivas, que recalculan una vez al
     * final; quien la use está obligado a hacerlo.
     */
    @Transactional
    public Split saveWithoutRecalc(Long userId, SplitForm form) {
        Split split = new Split();
        split.setUserId(userId);
        split.setTicker(form.getTicker().trim().toUpperCase());
        split.setDate(form.getDate());
        split.setRatio(form.getRatio());
        return splitRepo.save(split);
    }

    @Transactional
    public Split update(Long userId, Long id, SplitForm form) {
        Split split = requireOwned(userId, id);
        String oldTicker = split.getTicker();
        split.setTicker(form.getTicker().trim().toUpperCase());
        split.setDate(form.getDate());
        split.setRatio(form.getRatio());
        splitRepo.save(split);
        fifoService.recalculateFifo(userId, split.getTicker());
        if (!oldTicker.equals(split.getTicker())) {
            fifoService.recalculateFifo(userId, oldTicker);
        }
        return split;
    }

    @Transactional
    public void delete(Long userId, Long id) {
        Split split = requireOwned(userId, id);
        String ticker = split.getTicker();
        splitRepo.delete(split);
        fifoService.recalculateFifo(userId, ticker);
    }

    /** Carga el split comprobando el propietario. */
    public Split requireOwned(Long userId, Long id) {
        return splitRepo.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new IllegalArgumentException("Split no encontrado: " + id));
    }
}
