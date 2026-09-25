package io.github.ivarm1984.banksim.loan;

/**
 * A loan's IFRS 9 impairment stage, for every loan type: PERFORMING = Stage 1
 * (12-month ECL), UNDERPERFORMING = Stage 2 (30+ days past due, lifetime
 * ECL), NON_PERFORMING = Stage 3 (90+ days past due - defaulted per CRR
 * Art. 178, and non-performing per the EBA NPE definition). See
 * {@link CreditRisk} and {@link LoanStagingService}.
 */
public enum LoanPhase {
    PERFORMING,
    UNDERPERFORMING,
    NON_PERFORMING
}
