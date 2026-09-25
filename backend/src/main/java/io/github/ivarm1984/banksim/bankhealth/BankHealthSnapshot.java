package io.github.ivarm1984.banksim.bankhealth;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * One day's CEO-mode health status, with the breach streaks that produced it:
 * days below OCR ({@code capitalBreachStreak}), below TSCR
 * ({@code capitalShortfallStreak}), with NSFR below 100%
 * ({@code fundingBreachStreak}) and with uncovered liquidity
 * ({@code liquidityBreachStreak}).
 */
public record BankHealthSnapshot(
        Long id,
        LocalDate snapshotDate,
        BankHealthStatus status,
        int capitalBreachStreak,
        int capitalShortfallStreak,
        int fundingBreachStreak,
        int liquidityBreachStreak,
        OffsetDateTime createdAt) {
}
