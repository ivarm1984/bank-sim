package io.github.ivarm1984.banksim.treasury;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * One day's simplified treasury ratio snapshot. Ratio fields are nullable -
 * {@code null} means "not applicable yet" (a zero denominator, e.g.
 * net-stable-funding/capital-adequacy before any loan has been originated),
 * not zero or an error. {@code loansReceivable} is gross; NSFR/CAR are
 * computed on the net carrying amount, {@link #netLoans()}.
 * {@code nonPerformingLoanRatio} is gross Stage 3 loans / gross loans.
 */
public record TreasuryRatioSnapshot(
        Long id,
        LocalDate snapshotDate,
        BigDecimal bankCash,
        BigDecimal centralBankReserves,
        BigDecimal loansReceivable,
        BigDecimal loanLossProvision,
        BigDecimal customerDeposits,
        BigDecimal capitalBase,
        BigDecimal loanToDepositRatio,
        BigDecimal liquidityCoverageRatio,
        BigDecimal netStableFundingRatio,
        BigDecimal requiredReserves,
        BigDecimal reserveCoverageRatio,
        BigDecimal capitalAdequacyRatio,
        BigDecimal riskWeightedAssets,
        BigDecimal returnOnEquity,
        BigDecimal nonPerformingLoanRatio,
        OffsetDateTime createdAt) {

    /** Gross loans receivable less the loan-loss provision contra-asset. */
    public BigDecimal netLoans() {
        return loansReceivable.subtract(loanLossProvision);
    }
}
