package io.github.ivarm1984.banksim.bankhealth;

import java.time.LocalDate;

/**
 * Published once a day's bank-health status has been (re)computed. Not
 * published once a terminal status has been reached - see
 * {@link BankHealthService#computeAndPersist}.
 */
public record BankHealthUpdatedEvent(
        LocalDate date, BankHealthStatus status, int capitalBreachStreak, int capitalShortfallStreak,
        int fundingBreachStreak, int liquidityBreachStreak) {
}
