package io.github.ivarm1984.banksim.loan;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One row of a Loan's original amortization plan, computed once at origination and never modified by prepayments. */
public record LoanInstallment(
        Long id,
        Long loanId,
        int installmentNumber,
        LocalDate dueDate,
        BigDecimal openingBalance,
        BigDecimal interestPortion,
        BigDecimal principalPortion,
        BigDecimal closingBalance) {
}
