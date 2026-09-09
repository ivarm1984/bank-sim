package io.github.ivarm1984.banksim.loan;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** One phase transition (up or down) recorded against a BUSINESS Loan - the audit trail behind its current phase/provisionAmount. */
public record LoanPhaseTransition(
        Long id,
        Long loanId,
        LoanPhase fromPhase,
        LoanPhase toPhase,
        LocalDate transitionDate,
        BigDecimal provisionDelta,
        Long journalEntryId,
        OffsetDateTime createdAt) {
}
