package io.github.ivarm1984.banksim.loan;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

public record LoanAccount(
        Long id,
        Long customerId,
        Long disbursementAccountId,
        LoanType loanType,
        BigDecimal principal,
        BigDecimal annualRate,
        int termMonths,
        BigDecimal installmentAmount,
        LocalDate originationDate,
        BigDecimal outstandingPrincipal,
        int nextInstallmentNumber,
        LoanStatus status,
        LoanPhase phase,
        BigDecimal provisionAmount,
        OffsetDateTime createdAt) {
}
