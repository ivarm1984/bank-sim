package io.github.ivarm1984.banksim.loan;

import java.math.BigDecimal;

/** Published after a loan repayment (normal, early-partial, or early-payoff) has posted. */
public record LoanRepaidEvent(
        Long loanId, LoanPaymentType paymentType, BigDecimal amount, BigDecimal principalPortion,
        BigDecimal interestPortion, BigDecimal outstandingPrincipalAfter, boolean paidOff) {
}
