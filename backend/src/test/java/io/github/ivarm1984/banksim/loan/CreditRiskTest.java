package io.github.ivarm1984.banksim.loan;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

/**
 * Plain unit test (no Spring/Postgres) for {@link CreditRisk}'s pure
 * days-past-due staging state machine and ECL formula - same precedent as
 * BankHealthStatusTest.
 */
class CreditRiskTest {

    private static final LocalDate TODAY = LocalDate.of(2027, 6, 1);
    private static final BigDecimal EAD = new BigDecimal("100000.00");

    @Test
    void stageFollowsTheThirtyAndNinetyDayBackstops() {
        assertThat(CreditRisk.nextStaging(LoanPhase.PERFORMING, null, 29, TODAY).phase()).isEqualTo(LoanPhase.PERFORMING);
        assertThat(CreditRisk.nextStaging(LoanPhase.PERFORMING, null, 30, TODAY).phase()).isEqualTo(LoanPhase.UNDERPERFORMING);
        assertThat(CreditRisk.nextStaging(LoanPhase.PERFORMING, null, 90, TODAY).phase()).isEqualTo(LoanPhase.NON_PERFORMING);
        assertThat(CreditRisk.nextStaging(LoanPhase.UNDERPERFORMING, null, 0, TODAY).phase()).isEqualTo(LoanPhase.PERFORMING);
    }

    @Test
    void aDefaultedLoanStaysInDefaultWhileStillInArrearsEvenBelowNinetyDays() {
        CreditRisk.Staging next = CreditRisk.nextStaging(LoanPhase.NON_PERFORMING, null, 10, TODAY);
        assertThat(next.phase()).isEqualTo(LoanPhase.NON_PERFORMING);
        assertThat(next.probationStart()).isNull();
    }

    @Test
    void probationStartsWhenCurrentAndCuresOnlyAfterThreeMonths() {
        CreditRisk.Staging started = CreditRisk.nextStaging(LoanPhase.NON_PERFORMING, null, 0, TODAY);
        assertThat(started).isEqualTo(new CreditRisk.Staging(LoanPhase.NON_PERFORMING, TODAY));

        assertThat(CreditRisk.nextStaging(LoanPhase.NON_PERFORMING, TODAY, 0, TODAY.plusMonths(3).minusDays(1)))
                .isEqualTo(new CreditRisk.Staging(LoanPhase.NON_PERFORMING, TODAY));
        assertThat(CreditRisk.nextStaging(LoanPhase.NON_PERFORMING, TODAY, 0, TODAY.plusMonths(3)))
                .isEqualTo(new CreditRisk.Staging(LoanPhase.PERFORMING, null));
    }

    @Test
    void newArrearsDuringProbationRestartIt() {
        CreditRisk.Staging relapsed = CreditRisk.nextStaging(LoanPhase.NON_PERFORMING, TODAY, 5, TODAY.plusMonths(2));
        assertThat(relapsed).isEqualTo(new CreditRisk.Staging(LoanPhase.NON_PERFORMING, null));
    }

    @Test
    void eclRisesStageByStageAndStage3IsLgdTimesExposure() {
        BigDecimal stage1 = CreditRisk.expectedCreditLoss(LoanType.BUSINESS, LoanPhase.PERFORMING, EAD, 60, false);
        BigDecimal stage2 = CreditRisk.expectedCreditLoss(LoanType.BUSINESS, LoanPhase.UNDERPERFORMING, EAD, 60, false);
        BigDecimal stage3 = CreditRisk.expectedCreditLoss(LoanType.BUSINESS, LoanPhase.NON_PERFORMING, EAD, 60, false);

        assertThat(stage1).isEqualByComparingTo(new BigDecimal("675.00")); // 1.5% PD x 45% LGD
        assertThat(stage2).isGreaterThan(stage1);
        assertThat(stage3).isEqualByComparingTo(new BigDecimal("45000.00"));
    }

    @Test
    void lifetimeEclForAOneYearRemainingTermEqualsTheTwelveMonthEcl() {
        assertThat(CreditRisk.expectedCreditLoss(LoanType.CONSUMER, LoanPhase.UNDERPERFORMING, EAD, 12, false))
                .isEqualByComparingTo(CreditRisk.expectedCreditLoss(LoanType.CONSUMER, LoanPhase.PERFORMING, EAD, 12, false));
    }

    @Test
    void aRecessionRaisesPerformingEclButNotDefaultedEcl() {
        assertThat(CreditRisk.expectedCreditLoss(LoanType.MORTGAGE, LoanPhase.PERFORMING, EAD, 240, true))
                .isGreaterThan(CreditRisk.expectedCreditLoss(LoanType.MORTGAGE, LoanPhase.PERFORMING, EAD, 240, false));
        assertThat(CreditRisk.expectedCreditLoss(LoanType.MORTGAGE, LoanPhase.NON_PERFORMING, EAD, 240, true))
                .isEqualByComparingTo(CreditRisk.expectedCreditLoss(LoanType.MORTGAGE, LoanPhase.NON_PERFORMING, EAD, 240, false));
    }

    @Test
    void writeOffComesAfterTwelveMonthsInDefaultForUnsecuredLoansAndTwentyFourForMortgages() {
        LocalDate defaulted = TODAY.minusMonths(12);
        assertThat(CreditRisk.dueForWriteOff(LoanType.CONSUMER, defaulted.plusDays(1), null, TODAY)).isFalse();
        assertThat(CreditRisk.dueForWriteOff(LoanType.CONSUMER, defaulted, null, TODAY)).isTrue();
        assertThat(CreditRisk.dueForWriteOff(LoanType.BUSINESS, defaulted, null, TODAY)).isTrue();
        assertThat(CreditRisk.dueForWriteOff(LoanType.MORTGAGE, defaulted, null, TODAY)).isFalse();
        assertThat(CreditRisk.dueForWriteOff(LoanType.MORTGAGE, TODAY.minusMonths(24), null, TODAY)).isTrue();
    }

    @Test
    void aLoanOnCureProbationOrNeverDefaultedIsNeverDueForWriteOff() {
        assertThat(CreditRisk.dueForWriteOff(LoanType.CONSUMER, TODAY.minusYears(3), TODAY.minusDays(10), TODAY)).isFalse();
        assertThat(CreditRisk.dueForWriteOff(LoanType.CONSUMER, null, null, TODAY)).isFalse();
    }

    @Test
    void theRecoveryLeavesExactlyTheStage3AllowanceToWriteOff() {
        for (LoanType type : LoanType.values()) {
            BigDecimal recovery = CreditRisk.recoveryAmount(type, EAD);
            BigDecimal stage3Ecl = CreditRisk.expectedCreditLoss(type, LoanPhase.NON_PERFORMING, EAD, 12, false);
            assertThat(EAD.subtract(recovery)).isEqualByComparingTo(stage3Ecl);
        }
        assertThat(CreditRisk.recoveryAmount(LoanType.MORTGAGE, EAD)).isEqualByComparingTo("85000.00");
    }
}
