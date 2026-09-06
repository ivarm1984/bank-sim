package io.github.ivarm1984.banksim.loan;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** One actual repayment made against a Loan - the real history, as opposed to LoanInstallment's original plan. */
public record LoanPayment(
        Long id,
        Long loanId,
        LocalDate paymentDate,
        LoanPaymentType paymentType,
        BigDecimal amount,
        BigDecimal interestPortion,
        BigDecimal principalPortion,
        BigDecimal outstandingPrincipalAfter,
        Long journalEntryId,
        OffsetDateTime createdAt) {
}
