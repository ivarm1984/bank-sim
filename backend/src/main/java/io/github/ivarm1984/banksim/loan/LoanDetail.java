package io.github.ivarm1984.banksim.loan;

import java.util.List;

/** A Loan's header plus its full original amortization plan and actual payment history. */
public record LoanDetail(LoanAccount loan, List<LoanInstallment> installments, List<LoanPayment> payments) {
}
