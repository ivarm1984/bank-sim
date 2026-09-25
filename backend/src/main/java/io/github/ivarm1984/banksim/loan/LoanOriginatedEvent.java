package io.github.ivarm1984.banksim.loan;

import java.math.BigDecimal;

import io.github.ivarm1984.banksim.customer.CreditGrade;

/** Published after a loan has been originated and disbursed. {@code creditGrade} is null only for a loan priced before {@code loan-0010}. */
public record LoanOriginatedEvent(
        Long loanId, Long customerId, Long disbursementAccountId, LoanType loanType, BigDecimal principal,
        BigDecimal annualRate, int termMonths, CreditGrade creditGrade, BigDecimal probabilityOfDefault) {
}
