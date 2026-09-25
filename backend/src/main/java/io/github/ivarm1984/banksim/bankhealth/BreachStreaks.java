package io.github.ivarm1984.banksim.bankhealth;

/**
 * Consecutive-day breach counters, one per regulatory threshold - see
 * {@link BankHealthService}. Each resets to 0 on the first day its breach
 * clears.
 */
record BreachStreaks(int capital, int capitalShortfall, int funding, int liquidity) {

    static final BreachStreaks NONE = new BreachStreaks(0, 0, 0, 0);

    boolean allClear() {
        return capital == 0 && capitalShortfall == 0 && funding == 0 && liquidity == 0;
    }
}
