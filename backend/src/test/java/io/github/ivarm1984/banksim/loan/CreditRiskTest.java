package io.github.ivarm1984.banksim.loan;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import io.github.ivarm1984.banksim.customer.CreditGrade;

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
        BigDecimal stage1 = CreditRisk.expectedCreditLoss(LoanType.BUSINESS, CreditRisk.productDefaultProbability(LoanType.BUSINESS), LoanPhase.PERFORMING, EAD, 60, false);
        BigDecimal stage2 = CreditRisk.expectedCreditLoss(LoanType.BUSINESS, CreditRisk.productDefaultProbability(LoanType.BUSINESS), LoanPhase.UNDERPERFORMING, EAD, 60, false);
        BigDecimal stage3 = CreditRisk.expectedCreditLoss(LoanType.BUSINESS, CreditRisk.productDefaultProbability(LoanType.BUSINESS), LoanPhase.NON_PERFORMING, EAD, 60, false);

        assertThat(stage1).isEqualByComparingTo(new BigDecimal("675.00")); // 1.5% PD x 45% LGD
        assertThat(stage2).isGreaterThan(stage1);
        assertThat(stage3).isEqualByComparingTo(new BigDecimal("45000.00"));
    }

    @Test
    void lifetimeEclForAOneYearRemainingTermEqualsTheTwelveMonthEcl() {
        assertThat(CreditRisk.expectedCreditLoss(LoanType.CONSUMER, CreditRisk.productDefaultProbability(LoanType.CONSUMER), LoanPhase.UNDERPERFORMING, EAD, 12, false))
                .isEqualByComparingTo(CreditRisk.expectedCreditLoss(LoanType.CONSUMER, CreditRisk.productDefaultProbability(LoanType.CONSUMER), LoanPhase.PERFORMING, EAD, 12, false));
    }

    @Test
    void aRecessionRaisesPerformingEclButNotDefaultedEcl() {
        assertThat(CreditRisk.expectedCreditLoss(LoanType.MORTGAGE, CreditRisk.productDefaultProbability(LoanType.MORTGAGE), LoanPhase.PERFORMING, EAD, 240, true))
                .isGreaterThan(CreditRisk.expectedCreditLoss(LoanType.MORTGAGE, CreditRisk.productDefaultProbability(LoanType.MORTGAGE), LoanPhase.PERFORMING, EAD, 240, false));
        assertThat(CreditRisk.expectedCreditLoss(LoanType.MORTGAGE, CreditRisk.productDefaultProbability(LoanType.MORTGAGE), LoanPhase.NON_PERFORMING, EAD, 240, true))
                .isEqualByComparingTo(CreditRisk.expectedCreditLoss(LoanType.MORTGAGE, CreditRisk.productDefaultProbability(LoanType.MORTGAGE), LoanPhase.NON_PERFORMING, EAD, 240, false));
    }

    @Test
    void borrowerPdScalesTheProductBaselineByGradeAndDebtServiceToIncomeBand() {
        BigDecimal low = new BigDecimal("0.20");
        assertThat(CreditRisk.borrowerDefaultProbability(LoanType.CONSUMER, CreditGrade.C, low)).isEqualByComparingTo("0.025");
        assertThat(CreditRisk.borrowerDefaultProbability(LoanType.CONSUMER, CreditGrade.A, low)).isEqualByComparingTo("0.010");
        assertThat(CreditRisk.borrowerDefaultProbability(LoanType.CONSUMER, CreditGrade.E, low)).isEqualByComparingTo("0.075");

        assertThat(CreditRisk.borrowerDefaultProbability(LoanType.BUSINESS, CreditGrade.C, new BigDecimal("0.30"))).isEqualByComparingTo("0.015");
        assertThat(CreditRisk.borrowerDefaultProbability(LoanType.BUSINESS, CreditGrade.C, new BigDecimal("0.35"))).isEqualByComparingTo("0.0225");
        assertThat(CreditRisk.borrowerDefaultProbability(LoanType.BUSINESS, CreditGrade.C, new BigDecimal("0.45"))).isEqualByComparingTo("0.0375");
        assertThat(CreditRisk.borrowerDefaultProbability(LoanType.BUSINESS, CreditGrade.C, new BigDecimal("0.55"))).isEqualByComparingTo("0.060");
    }

    @Test
    void borrowerPdIsCappedAndTheRecessionMultiplierNeverTakesItAboveOne() {
        // Consumer 2.5% x 3.0 (E) x 4.0 (DSTI > 50%) = 30% - under the 50% cap.
        assertThat(CreditRisk.borrowerDefaultProbability(LoanType.CONSUMER, CreditGrade.E, new BigDecimal("0.58"))).isEqualByComparingTo("0.30");
        assertThat(CreditRisk.twelveMonthDefaultProbability(new BigDecimal("0.30"), true)).isEqualByComparingTo("0.90");
        assertThat(CreditRisk.twelveMonthDefaultProbability(new BigDecimal("0.50"), true)).isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    void theLoosenessLeverMapsLinearlyOntoTheApprovalCutoff() {
        assertThat(CreditRisk.maxApprovalDefaultProbability(BigDecimal.ZERO)).isEqualByComparingTo("0.02");
        assertThat(CreditRisk.maxApprovalDefaultProbability(new BigDecimal("0.5"))).isEqualByComparingTo("0.16");
        assertThat(CreditRisk.maxApprovalDefaultProbability(BigDecimal.ONE)).isEqualByComparingTo("0.30");
    }

    @Test
    void aRiskierBorrowerCarriesAProportionallyLargerStage1Allowance() {
        BigDecimal average = CreditRisk.expectedCreditLoss(LoanType.CONSUMER, new BigDecimal("0.025"), LoanPhase.PERFORMING, EAD, 24, false);
        BigDecimal subprime = CreditRisk.expectedCreditLoss(LoanType.CONSUMER, new BigDecimal("0.075"), LoanPhase.PERFORMING, EAD, 24, false);
        assertThat(subprime).isEqualByComparingTo(average.multiply(new BigDecimal("3")));
        // Stage 3 is LGD x EAD whoever the borrower was.
        assertThat(CreditRisk.expectedCreditLoss(LoanType.CONSUMER, new BigDecimal("0.075"), LoanPhase.NON_PERFORMING, EAD, 24, false))
                .isEqualByComparingTo(CreditRisk.expectedCreditLoss(LoanType.CONSUMER, new BigDecimal("0.025"), LoanPhase.NON_PERFORMING, EAD, 24, false));
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
            BigDecimal stage3Ecl = CreditRisk.expectedCreditLoss(type, CreditRisk.productDefaultProbability(type), LoanPhase.NON_PERFORMING, EAD, 12, false);
            assertThat(EAD.subtract(recovery)).isEqualByComparingTo(stage3Ecl);
        }
        assertThat(CreditRisk.recoveryAmount(LoanType.MORTGAGE, EAD)).isEqualByComparingTo("85000.00");
    }
}
