package io.github.ivarm1984.banksim.loan;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Published after a BUSINESS loan's phase transition (up or down) has posted its provisioning entry. */
public record LoanPhaseChangedEvent(
        Long loanId, LoanPhase fromPhase, LoanPhase toPhase, LocalDate transitionDate,
        BigDecimal provisionDelta, BigDecimal newProvisionAmount, Long journalEntryId) {
}
