package io.github.ivarm1984.banksim.policy;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Component;

/**
 * Holds the CEO's current policy-lever state - the runtime-mutable
 * counterparts to what used to be {@code static final} constants in
 * {@code LoanService}, {@code TreasuryService}, and
 * {@code interest.rate_policies}. Modeled directly on
 * {@code clock.SimulationClock}'s {@code AtomicReference<Snapshot>} pattern:
 * pure in-memory state, no schema.
 *
 * <p>{@code underwritingLooseness} is deliberately not consumed by anything
 * yet - there is no credit-scoring/per-borrower risk system in this codebase
 * for it to act on (see TODO.md's "Complex additions" - "Credit risk
 * pricing"). It exists as a placeholder field for that future work.
 */
@Component
public class PolicyLevers {

    static final BigDecimal DEFAULT_SAVINGS_RATE_SPREAD = new BigDecimal("-0.0150");
    static final BigDecimal DEFAULT_SPREAD_ADJUSTMENT = BigDecimal.ZERO;
    static final BigDecimal DEFAULT_CAPITAL_BUFFER = BigDecimal.ZERO;
    static final BigDecimal DEFAULT_UNDERWRITING_LOOSENESS = new BigDecimal("0.50");
    static final boolean DEFAULT_AUTO_TAP = true;

    private final AtomicReference<PolicyLeversSnapshot> state = new AtomicReference<>(new PolicyLeversSnapshot(
            DEFAULT_SAVINGS_RATE_SPREAD, DEFAULT_SPREAD_ADJUSTMENT, DEFAULT_SPREAD_ADJUSTMENT, DEFAULT_SPREAD_ADJUSTMENT,
            DEFAULT_CAPITAL_BUFFER, DEFAULT_UNDERWRITING_LOOSENESS, DEFAULT_AUTO_TAP));

    public PolicyLeversSnapshot state() {
        return state.get();
    }

    public PolicyLeversSnapshot update(PolicyLeversSnapshot next) {
        return state.updateAndGet(s -> next);
    }
}
