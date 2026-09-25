package io.github.ivarm1984.banksim.loan;

import java.util.List;

/**
 * A Loan's header plus its full original amortization plan, actual payment
 * history, IFRS 9 stage history, and its write-off (null unless
 * {@link LoanStatus#WRITTEN_OFF}).
 */
public record LoanDetail(
        LoanAccount loan, List<LoanInstallment> installments, List<LoanPayment> payments,
        List<LoanPhaseTransition> phaseHistory, LoanWriteOff writeOff) {
}
