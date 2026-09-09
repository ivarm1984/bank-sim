package io.github.ivarm1984.banksim.loan;

/**
 * Loan-loss provisioning phase, meaningful only for {@link LoanType#BUSINESS}
 * loans - MORTGAGE/CONSUMER loans stay PERFORMING for their whole life. See
 * {@code LoanPhaseTransitionService}.
 */
public enum LoanPhase {
    PERFORMING,
    UNDERPERFORMING,
    NON_PERFORMING
}
