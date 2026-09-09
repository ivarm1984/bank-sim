package io.github.ivarm1984.banksim.loan;

import java.util.List;

/** A Loan's header plus its full original amortization plan, actual payment history, and phase history (BUSINESS loans only). */
public record LoanDetail(
        LoanAccount loan, List<LoanInstallment> installments, List<LoanPayment> payments,
        List<LoanPhaseTransition> phaseHistory) {
}
