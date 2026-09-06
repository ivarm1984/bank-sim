package io.github.ivarm1984.banksim.loan;

import java.math.BigDecimal;

/** Published after a loan has been originated and disbursed. */
public record LoanOriginatedEvent(
        Long loanId, Long customerId, Long disbursementAccountId, BigDecimal principal, BigDecimal annualRate, int termMonths) {
}
