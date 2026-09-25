package io.github.ivarm1984.banksim.loan;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Published after a defaulted loan's write-off has posted - see {@link LoanWriteOffService}. */
public record LoanWrittenOffEvent(
        Long loanId, LoanType loanType, LocalDate writeOffDate, BigDecimal outstandingPrincipal,
        BigDecimal recoveryAmount, BigDecimal writtenOffAmount, Long journalEntryId) {
}
