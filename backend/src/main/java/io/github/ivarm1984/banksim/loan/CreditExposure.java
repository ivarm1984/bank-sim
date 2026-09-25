package io.github.ivarm1984.banksim.loan;

import java.math.BigDecimal;

/**
 * Active loans of one product in one IFRS 9 stage, aggregated: gross
 * outstanding principal and the loss allowance held against it. Treasury
 * builds credit-risk RWA and the central bank collateral pool from these.
 */
public record CreditExposure(LoanType loanType, LoanPhase phase, BigDecimal outstandingPrincipal, BigDecimal provision) {

    /** Exposure value net of specific credit risk adjustments (CRR Art. 111). */
    public BigDecimal netExposure() {
        return outstandingPrincipal.subtract(provision);
    }

    /** Defaulted - 90+ days past due, IFRS 9 Stage 3. */
    public boolean defaulted() {
        return phase == LoanPhase.NON_PERFORMING;
    }
}
