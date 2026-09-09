package io.github.ivarm1984.banksim.bankhealth;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** One day's CEO-mode health status, with the breach streaks that produced it. */
public record BankHealthSnapshot(
        Long id,
        LocalDate snapshotDate,
        BankHealthStatus status,
        int capitalBreachStreak,
        int liquidityBreachStreak,
        OffsetDateTime createdAt) {
}
