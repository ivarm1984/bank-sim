package io.github.ivarm1984.banksim.loan;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * Credit-risk parameters and the pure IFRS 9 staging/expected-credit-loss
 * math, shared by {@link LoanStagingService} (daily staging, macro
 * remeasurement), {@link LoanService} (provision at origination and after
 * each repayment) and {@code agents.BorrowerAgent} (how often borrowers get
 * into payment difficulty). Every figure is an illustrative constant in the
 * right ballpark for an EU retail/SME book, not a calibrated model.
 *
 * <p>ECL = PD x LGD x EAD, with EAD = outstanding principal:
 * <ul>
 *   <li>Stage 1 ({@link LoanPhase#PERFORMING}): 12-month PD.</li>
 *   <li>Stage 2 ({@link LoanPhase#UNDERPERFORMING}, significant increase in
 *       credit risk - here the 30-days-past-due backstop of IFRS 9 5.5.11):
 *       lifetime PD over the remaining term, {@code 1 - (1 - PD12)^years}.</li>
 *   <li>Stage 3 ({@link LoanPhase#NON_PERFORMING}, credit-impaired/defaulted
 *       - 90+ days past due, CRR Art. 178): PD = 1, so ECL = LGD x EAD.</li>
 * </ul>
 * IFRS 9 ECL is forward-looking, so PDs are scaled up while a recession is
 * active - the whole book's provision moves with the macro scenario, not
 * just the loans that have already missed payments.
 */
public final class CreditRisk {

    /** Stage 2 backstop - IFRS 9 5.5.11's rebuttable 30-days-past-due presumption. */
    static final int STAGE_2_DAYS_PAST_DUE = 30;
    /** Default - CRR Art. 178(1)(b). */
    static final int DEFAULT_DAYS_PAST_DUE = 90;
    /**
     * Minimum probation before a defaulted exposure may be reclassified out
     * of default once the conditions have ceased (EBA/GL/2016/07 para. 71).
     * Forborne exposures need a year (para. 72) - forbearance isn't modeled.
     */
    static final int DEFAULT_PROBATION_MONTHS = 3;

    /** Forward-looking IFRS 9 adjustment applied to every PD while a recession is active. */
    static final BigDecimal RECESSION_PD_MULTIPLIER = new BigDecimal("3");

    private static final BigDecimal MONTHS_PER_YEAR = new BigDecimal("12");

    private CreditRisk() {
    }

    /** Baseline 12-month probability of default by product. */
    public static BigDecimal twelveMonthDefaultProbability(LoanType type, boolean recessionActive) {
        BigDecimal baseline = switch (type) {
            case MORTGAGE -> new BigDecimal("0.005");
            case CONSUMER -> new BigDecimal("0.025");
            case BUSINESS -> new BigDecimal("0.015");
        };
        return recessionActive ? baseline.multiply(RECESSION_PD_MULTIPLIER) : baseline;
    }

    /**
     * Loss given default by product - mortgages are secured on the home (low
     * LGD), consumer loans are unsecured (high), business loans sit at the
     * CRR foundation-IRB 45% for senior unsecured corporate exposures.
     */
    static BigDecimal lossGivenDefault(LoanType type) {
        return switch (type) {
            case MORTGAGE -> new BigDecimal("0.15");
            case CONSUMER -> new BigDecimal("0.60");
            case BUSINESS -> new BigDecimal("0.45");
        };
    }

    /** Pure IFRS 9 ECL for one loan - see the class docs. */
    static BigDecimal expectedCreditLoss(
            LoanType type, LoanPhase phase, BigDecimal outstandingPrincipal, int remainingMonths, boolean recessionActive) {
        if (outstandingPrincipal.signum() <= 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        BigDecimal pd12 = twelveMonthDefaultProbability(type, recessionActive).min(BigDecimal.ONE);
        BigDecimal pd = switch (phase) {
            case PERFORMING -> pd12;
            case UNDERPERFORMING -> lifetimeDefaultProbability(pd12, remainingMonths);
            case NON_PERFORMING -> BigDecimal.ONE;
        };
        return outstandingPrincipal.multiply(pd).multiply(lossGivenDefault(type)).setScale(2, RoundingMode.HALF_UP);
    }

    /** {@code 1 - (1 - PD12)^(remainingMonths / 12)} - never below the 12-month PD. */
    static BigDecimal lifetimeDefaultProbability(BigDecimal pd12, int remainingMonths) {
        double years = Math.max(1, remainingMonths) / MONTHS_PER_YEAR.doubleValue();
        double survival = Math.pow(1 - pd12.doubleValue(), years);
        return new BigDecimal(1 - survival, MathContext.DECIMAL64).max(pd12).min(BigDecimal.ONE);
    }

    /** Remaining contractual installments, at least one while the loan is still active. */
    static int remainingMonths(LoanAccount loan) {
        return Math.max(1, loan.termMonths() - (loan.nextInstallmentNumber() - 1));
    }

    /** A loan's stage plus, while in default, the date its probation started (null if not on probation). */
    record Staging(LoanPhase phase, LocalDate probationStart) {
    }

    /**
     * Pure days-past-due staging state machine. Entering default (90+ DPD)
     * is immediate; leaving it needs {@link #DEFAULT_PROBATION_MONTHS} of
     * being fully current (DPD 0) - any new arrears during probation restart
     * the clock. Outside default, the stage simply follows the 30-DPD
     * backstop.
     */
    static Staging nextStaging(LoanPhase current, LocalDate probationStart, int daysPastDue, LocalDate today) {
        if (daysPastDue >= DEFAULT_DAYS_PAST_DUE) {
            return new Staging(LoanPhase.NON_PERFORMING, null);
        }
        if (current == LoanPhase.NON_PERFORMING) {
            if (daysPastDue > 0) {
                return new Staging(LoanPhase.NON_PERFORMING, null);
            }
            LocalDate start = probationStart == null ? today : probationStart;
            if (!today.isBefore(start.plusMonths(DEFAULT_PROBATION_MONTHS))) {
                return new Staging(LoanPhase.PERFORMING, null);
            }
            return new Staging(LoanPhase.NON_PERFORMING, start);
        }
        return new Staging(daysPastDue >= STAGE_2_DAYS_PAST_DUE ? LoanPhase.UNDERPERFORMING : LoanPhase.PERFORMING, null);
    }
}
