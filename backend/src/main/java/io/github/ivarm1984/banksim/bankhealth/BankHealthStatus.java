package io.github.ivarm1984.banksim.bankhealth;

/**
 * CEO-mode game state, derived daily from {@code TreasuryRatiosUpdatedEvent}
 * breach streaks - see {@link BankHealthService}. {@code GAME_OVER},
 * {@code BANK_RUN} and {@code WON} are terminal: once reached, the state
 * machine stops evaluating further days (see AGENTS.md-style rationale in
 * {@code BankHealthService#computeAndPersist}).
 */
public enum BankHealthStatus {
    PLAYING,
    WARNING,
    GAME_OVER,
    BANK_RUN,
    WON;

    public boolean isTerminal() {
        return this == GAME_OVER || this == BANK_RUN || this == WON;
    }
}
