package io.github.ivarm1984.banksim.loan;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

/**
 * Plain unit test (no Spring/Postgres) for {@link LoanPhaseTransitionService}'s
 * pure phase state machine and provisioning formula, isolated from the
 * shared-container persistence concerns the rest of the suite has to work
 * around (see LoanPhaseTransitionServiceTest). Modeled on
 * BankHealthStatusTest's plain-unit-test precedent for exactly this kind of
 * pure logic.
 */
class LoanPhaseTransitionLogicTest {

    private static final double JUST_BELOW_BASELINE = LoanPhaseTransitionService.BASELINE_DOWNGRADE_DAILY_PROBABILITY - 0.00001;
    private static final double BETWEEN_BASELINE_AND_RECESSION =
            (LoanPhaseTransitionService.BASELINE_DOWNGRADE_DAILY_PROBABILITY + LoanPhaseTransitionService.RECESSION_DOWNGRADE_DAILY_PROBABILITY) / 2;
    private static final double JUST_BELOW_RECOVERY = LoanPhaseTransitionService.RECOVERY_DAILY_PROBABILITY - 0.00001;
    private static final double NEVER_ROLLS_UNDER = 0.999999;

    @Test
    void baselineDowngradeFiresOutsideARecessionWhenRollIsBelowBaseline() {
        LoanPhase next = LoanPhaseTransitionService.nextPhase(LoanPhase.PERFORMING, false, JUST_BELOW_BASELINE, NEVER_ROLLS_UNDER);
        assertThat(next).isEqualTo(LoanPhase.UNDERPERFORMING);
    }

    @Test
    void aRollBetweenBaselineAndRecessionRatesOnlyFiresDuringARecession() {
        assertThat(LoanPhaseTransitionService.nextPhase(LoanPhase.PERFORMING, false, BETWEEN_BASELINE_AND_RECESSION, NEVER_ROLLS_UNDER))
                .isEqualTo(LoanPhase.PERFORMING);
        assertThat(LoanPhaseTransitionService.nextPhase(LoanPhase.PERFORMING, true, BETWEEN_BASELINE_AND_RECESSION, NEVER_ROLLS_UNDER))
                .isEqualTo(LoanPhase.UNDERPERFORMING);
    }

    @Test
    void underperformingDowngradesToNonPerforming() {
        LoanPhase next = LoanPhaseTransitionService.nextPhase(LoanPhase.UNDERPERFORMING, true, JUST_BELOW_BASELINE, NEVER_ROLLS_UNDER);
        assertThat(next).isEqualTo(LoanPhase.NON_PERFORMING);
    }

    @Test
    void nonPerformingNeverDowngradesFurtherEvenOnAGuaranteedDowngradeRoll() {
        LoanPhase next = LoanPhaseTransitionService.nextPhase(LoanPhase.NON_PERFORMING, true, 0.0, NEVER_ROLLS_UNDER);
        assertThat(next).isEqualTo(LoanPhase.NON_PERFORMING);
    }

    @Test
    void recoveryFiresIndependentlyOfRecessionState() {
        assertThat(LoanPhaseTransitionService.nextPhase(LoanPhase.NON_PERFORMING, false, NEVER_ROLLS_UNDER, JUST_BELOW_RECOVERY))
                .isEqualTo(LoanPhase.UNDERPERFORMING);
        assertThat(LoanPhaseTransitionService.nextPhase(LoanPhase.NON_PERFORMING, true, NEVER_ROLLS_UNDER, JUST_BELOW_RECOVERY))
                .isEqualTo(LoanPhase.UNDERPERFORMING);
        assertThat(LoanPhaseTransitionService.nextPhase(LoanPhase.UNDERPERFORMING, false, NEVER_ROLLS_UNDER, JUST_BELOW_RECOVERY))
                .isEqualTo(LoanPhase.PERFORMING);
    }

    @Test
    void performingNeverUpgradesFurther() {
        LoanPhase next = LoanPhaseTransitionService.nextPhase(LoanPhase.PERFORMING, false, NEVER_ROLLS_UNDER, 0.0);
        assertThat(next).isEqualTo(LoanPhase.PERFORMING);
    }

    @Test
    void noTransitionWhenBothRollsMiss() {
        LoanPhase next = LoanPhaseTransitionService.nextPhase(LoanPhase.UNDERPERFORMING, false, NEVER_ROLLS_UNDER, NEVER_ROLLS_UNDER);
        assertThat(next).isEqualTo(LoanPhase.UNDERPERFORMING);
    }

    @Test
    void provisionAmountIsZeroForPerforming() {
        assertThat(LoanPhaseTransitionService.provisionAmount(LoanPhase.PERFORMING, new BigDecimal("100000.00")))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void provisionAmountIsTenPercentForUnderperforming() {
        assertThat(LoanPhaseTransitionService.provisionAmount(LoanPhase.UNDERPERFORMING, new BigDecimal("100000.00")))
                .isEqualByComparingTo(new BigDecimal("10000.00"));
    }

    @Test
    void provisionAmountIsFiftyPercentForNonPerforming() {
        assertThat(LoanPhaseTransitionService.provisionAmount(LoanPhase.NON_PERFORMING, new BigDecimal("100000.00")))
                .isEqualByComparingTo(new BigDecimal("50000.00"));
    }
}
