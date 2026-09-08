package io.github.ivarm1984.banksim.policy;

import java.math.BigDecimal;

/**
 * The CEO's current policy-lever settings. Every field is additive on top of
 * an existing base constant, so a lever at its default value reproduces
 * today's exact behavior (see {@link PolicyLevers}'s defaults).
 */
public record PolicyLeversSnapshot(
        BigDecimal savingsRateSpread,
        BigDecimal mortgageSpreadAdjustment,
        BigDecimal consumerSpreadAdjustment,
        BigDecimal targetCapitalBuffer,
        BigDecimal underwritingLooseness,
        boolean autoTapBorrowingFacility) {
}
