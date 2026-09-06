package io.github.ivarm1984.banksim.treasury;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * One day's simplified treasury ratio snapshot. Ratio fields are nullable -
 * {@code null} means "not applicable yet" (a zero denominator, e.g.
 * net-stable-funding/capital-adequacy before any loan has been originated),
 * not zero or an error.
 */
public record TreasuryRatioSnapshot(
        Long id,
        LocalDate snapshotDate,
        BigDecimal bankCash,
        BigDecimal centralBankReserves,
        BigDecimal loansReceivable,
        BigDecimal customerDeposits,
        BigDecimal capitalBase,
        BigDecimal loanToDepositRatio,
        BigDecimal liquidityCoverageRatio,
        BigDecimal netStableFundingRatio,
        BigDecimal requiredReserves,
        BigDecimal reserveCoverageRatio,
        BigDecimal capitalAdequacyRatio,
        OffsetDateTime createdAt) {
}
