package io.github.ivarm1984.banksim.loan;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;

import io.github.ivarm1984.banksim.customer.CreditGrade;

/**
 * Credit-risk parameters and the pure IFRS 9 staging/expected-credit-loss
 * math, shared by {@link LoanStagingService} (daily staging, macro
 * remeasurement), {@link LoanService} (PD and approval at origination,
 * provision at origination and after each repayment) and
 * {@code agents.BorrowerAgent} (how often borrowers get into payment
 * difficulty). Every figure is an illustrative constant in the right
 * ballpark for an EU retail/SME book, not a calibrated model.
 *
 * <p>Each loan carries its own baseline 12-month PD, fixed at origination:
 * the product's baseline PD x the borrower's {@link CreditGrade} multiplier x
 * a debt-service-to-income (DSTI) multiplier - see
 * {@link #borrowerDefaultProbability}. A borrower whose DSTI would exceed
 * {@link #MAX_DEBT_SERVICE_TO_INCOME} is declined outright.
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

    /**
     * Hard affordability limit: all the borrower's installments, this loan's
     * included, may take at most this share of monthly income - whatever the
     * CEO's risk appetite. Several EU countries set borrower-based DSTI
     * limits of this kind as a macroprudential measure (typically 40-60%).
     */
    static final BigDecimal MAX_DEBT_SERVICE_TO_INCOME = new BigDecimal("0.60");

    /** No borrower is ever rated worse than this 12-month PD. */
    static final BigDecimal MAX_DEFAULT_PROBABILITY = new BigDecimal("0.50");

    /*
     * The underwriting cutoff: the CEO's underwritingLooseness lever (0..1)
     * maps linearly onto the highest PD still approved - 2% (only the best
     * borrowers) at 0, 30% (nearly everyone) at 1, 16% at the default 0.5.
     */
    static final BigDecimal MIN_APPROVAL_DEFAULT_PROBABILITY = new BigDecimal("0.02");
    static final BigDecimal MAX_APPROVAL_DEFAULT_PROBABILITY = new BigDecimal("0.30");

    private static final BigDecimal MONTHS_PER_YEAR = new BigDecimal("12");
    private static final int PD_SCALE = 6;

    private CreditRisk() {
    }

    /** Baseline 12-month probability of default by product - an average (grade C, low DSTI) borrower. */
    static BigDecimal productDefaultProbability(LoanType type) {
        return switch (type) {
            case MORTGAGE -> new BigDecimal("0.005");
            case CONSUMER -> new BigDecimal("0.025");
            case BUSINESS -> new BigDecimal("0.015");
        };
    }

    /** How much riskier (or safer) than an average borrower each grade is. */
    static BigDecimal gradeMultiplier(CreditGrade grade) {
        return switch (grade) {
            case A -> new BigDecimal("0.4");
            case B -> new BigDecimal("0.7");
            case C -> BigDecimal.ONE;
            case D -> new BigDecimal("1.8");
            case E -> new BigDecimal("3.0");
        };
    }

    /**
     * The more of their income a borrower already owes, the less room they
     * have to absorb a shock - PD rises in bands of debt service to income.
     */
    static BigDecimal debtServiceToIncomeMultiplier(BigDecimal debtServiceToIncome) {
        if (debtServiceToIncome.compareTo(new BigDecimal("0.30")) <= 0) {
            return BigDecimal.ONE;
        }
        if (debtServiceToIncome.compareTo(new BigDecimal("0.40")) <= 0) {
            return new BigDecimal("1.5");
        }
        if (debtServiceToIncome.compareTo(new BigDecimal("0.50")) <= 0) {
            return new BigDecimal("2.5");
        }
        return new BigDecimal("4.0");
    }

    /** A borrower's baseline 12-month PD for one loan: product x grade x DSTI, capped at {@link #MAX_DEFAULT_PROBABILITY}. */
    static BigDecimal borrowerDefaultProbability(LoanType type, CreditGrade grade, BigDecimal debtServiceToIncome) {
        return productDefaultProbability(type)
                .multiply(gradeMultiplier(grade))
                .multiply(debtServiceToIncomeMultiplier(debtServiceToIncome))
                .min(MAX_DEFAULT_PROBABILITY)
                .setScale(PD_SCALE, RoundingMode.HALF_UP);
    }

    /** A loan's baseline 12-month PD, scaled up while a recession is active. */
    public static BigDecimal twelveMonthDefaultProbability(BigDecimal baselinePd, boolean recessionActive) {
        BigDecimal pd = recessionActive ? baselinePd.multiply(RECESSION_PD_MULTIPLIER) : baselinePd;
        return pd.min(BigDecimal.ONE);
    }

    /** The highest (recession-adjusted) PD approved at {@code underwritingLooseness} - see the cutoff constants. */
    static BigDecimal maxApprovalDefaultProbability(BigDecimal underwritingLooseness) {
        BigDecimal range = MAX_APPROVAL_DEFAULT_PROBABILITY.subtract(MIN_APPROVAL_DEFAULT_PROBABILITY);
        return MIN_APPROVAL_DEFAULT_PROBABILITY.add(range.multiply(underwritingLooseness)).setScale(PD_SCALE, RoundingMode.HALF_UP);
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
            LoanType type, BigDecimal baselinePd, LoanPhase phase, BigDecimal outstandingPrincipal, int remainingMonths,
            boolean recessionActive) {
        if (outstandingPrincipal.signum() <= 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        BigDecimal pd12 = twelveMonthDefaultProbability(baselinePd, recessionActive);
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

    /**
     * Months continuously in default after which a loan with no prospect of
     * curing is written off (IFRS 9 5.4.4: no reasonable expectation of
     * recovering the rest). Mortgages get longer - repossessing and selling
     * the home takes time. Illustrative figures, well inside the ECB's NPL
     * backstop horizons (Reg. 2019/630), which would outlast a CEO-mode game.
     */
    static final int UNSECURED_WRITE_OFF_MONTHS = 12;
    static final int MORTGAGE_WRITE_OFF_MONTHS = 24;

    /**
     * Due for write-off: in default since at least the product's write-off
     * horizon and not on cure probation - a loan that's fully current again
     * still has a reasonable expectation of recovery.
     */
    static boolean dueForWriteOff(LoanType type, LocalDate defaultedSince, LocalDate probationStart, LocalDate today) {
        if (defaultedSince == null || probationStart != null) {
            return false;
        }
        int months = type == LoanType.MORTGAGE ? MORTGAGE_WRITE_OFF_MONTHS : UNSECURED_WRITE_OFF_MONTHS;
        return !today.isBefore(defaultedSince.plusMonths(months));
    }

    /**
     * What the bank recovers at write-off - the collateral sale (mortgage) or
     * debt sale (unsecured): {@code (1 - LGD) x outstanding}, so the part
     * written off equals the Stage 3 allowance already held against it.
     */
    static BigDecimal recoveryAmount(LoanType type, BigDecimal outstandingPrincipal) {
        return outstandingPrincipal.multiply(BigDecimal.ONE.subtract(lossGivenDefault(type))).setScale(2, RoundingMode.HALF_UP);
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
