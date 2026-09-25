package io.github.ivarm1984.banksim.loan;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * A defaulted loan's write-off: {@code outstandingPrincipal} derecognised,
 * {@code recoveryAmount} received from the collateral/debt sale, and the
 * remaining {@code writtenOffAmount} charged against the {@code allowanceUsed}
 * (any difference between the two went to PROVISION_EXPENSE).
 */
public record LoanWriteOff(
        Long id,
        Long loanId,
        LocalDate writeOffDate,
        LocalDate defaultedSince,
        BigDecimal outstandingPrincipal,
        BigDecimal recoveryAmount,
        BigDecimal writtenOffAmount,
        BigDecimal allowanceUsed,
        Long journalEntryId,
        OffsetDateTime createdAt) {
}
