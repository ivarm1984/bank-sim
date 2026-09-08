package io.github.ivarm1984.banksim.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class PolicyLeversTest {

    @Test
    void startsAtDocumentedDefaults() {
        PolicyLevers levers = new PolicyLevers();

        PolicyLeversSnapshot state = levers.state();

        assertThat(state.savingsRateSpread()).isEqualByComparingTo(PolicyLevers.DEFAULT_SAVINGS_RATE_SPREAD);
        assertThat(state.mortgageSpreadAdjustment()).isEqualByComparingTo(PolicyLevers.DEFAULT_SPREAD_ADJUSTMENT);
        assertThat(state.consumerSpreadAdjustment()).isEqualByComparingTo(PolicyLevers.DEFAULT_SPREAD_ADJUSTMENT);
        assertThat(state.targetCapitalBuffer()).isEqualByComparingTo(PolicyLevers.DEFAULT_CAPITAL_BUFFER);
        assertThat(state.underwritingLooseness()).isEqualByComparingTo(PolicyLevers.DEFAULT_UNDERWRITING_LOOSENESS);
        assertThat(state.autoTapBorrowingFacility()).isEqualTo(PolicyLevers.DEFAULT_AUTO_TAP);
    }

    @Test
    void updateFullyReplacesTheSnapshot() {
        PolicyLevers levers = new PolicyLevers();
        PolicyLeversSnapshot next = new PolicyLeversSnapshot(
                new BigDecimal("0.0025"), new BigDecimal("-0.0050"), new BigDecimal("0.0100"),
                new BigDecimal("0.02"), new BigDecimal("0.75"), false);

        PolicyLeversSnapshot returned = levers.update(next);

        assertThat(returned).isEqualTo(next);
        assertThat(levers.state()).isEqualTo(next);
    }
}
