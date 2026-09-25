package io.github.ivarm1984.banksim.loan;

import java.math.BigDecimal;
import java.math.RoundingMode;

import io.github.ivarm1984.banksim.customer.CreditGrade;
import io.github.ivarm1984.banksim.treasury.TreasuryService;

/**
 * Cost-based loan pricing - the textbook build-up of a loan's rate from
 * what it costs the bank to hold it:
 * <pre>
 *   rate = funding (central bank policy rate)
 *        + base margin       (operating cost and profit, per product)
 *        + expected loss     (PD x LGD - the annual cost of defaults)
 *        + capital charge    (risk weight x capital requirement x hurdle rate)
 *        + CEO's per-product spread adjustment
 * </pre>
 * The base margins are set so that an average (grade C, low DSTI) borrower
 * pays roughly the flat per-product spread this replaced.
 */
final class RiskBasedPricing {

    /**
     * Return the bank's equity is expected to earn on top of what the same
     * money would cost as deposit funding - the cost of the capital a loan
     * ties up. Illustrative, in the range of EU banks' cost-of-equity estimates.
     */
    static final BigDecimal CAPITAL_HURDLE_RATE = new BigDecimal("0.10");

    private static final int RATE_SCALE = 6;
    /** {@code loans.annual_rate} is numeric(6,4) - rates are quoted to a basis point. */
    private static final int ANNUAL_RATE_SCALE = 4;

    private RiskBasedPricing() {
    }

    static BigDecimal baseMargin(LoanType type) {
        return switch (type) {
            case MORTGAGE -> new BigDecimal("0.0099");
            case CONSUMER -> new BigDecimal("0.0656");
            case BUSINESS -> new BigDecimal("0.0308");
        };
    }

    static BigDecimal expectedLossSpread(LoanType type, BigDecimal pd) {
        return pd.multiply(CreditRisk.lossGivenDefault(type)).setScale(RATE_SCALE, RoundingMode.HALF_UP);
    }

    /** {@code capitalRequirement} is the CAR the bank targets - the overall requirement plus the CEO's management buffer. */
    static BigDecimal capitalSpread(LoanType type, BigDecimal capitalRequirement) {
        return TreasuryService.performingRiskWeight(type)
                .multiply(capitalRequirement)
                .multiply(CAPITAL_HURDLE_RATE)
                .setScale(RATE_SCALE, RoundingMode.HALF_UP);
    }

    /** The rate before the expected-loss component - what affordability (DSTI) is assessed at, see {@code LoanService}. */
    static BigDecimal rateBeforeCreditRisk(
            LoanType type, BigDecimal policyRate, BigDecimal capitalRequirement, BigDecimal spreadAdjustment) {
        return annualRate(policyRate.add(baseMargin(type)).add(capitalSpread(type, capitalRequirement)).add(spreadAdjustment));
    }

    static LoanPricing price(
            LoanType type, CreditGrade grade, BigDecimal monthlyIncome, BigDecimal debtServiceToIncome, BigDecimal pricingPd,
            BigDecimal policyRate, BigDecimal capitalRequirement, BigDecimal spreadAdjustment) {
        BigDecimal baseMargin = baseMargin(type);
        BigDecimal expectedLoss = expectedLossSpread(type, pricingPd);
        BigDecimal capital = capitalSpread(type, capitalRequirement);
        BigDecimal rate = annualRate(policyRate.add(baseMargin).add(expectedLoss).add(capital).add(spreadAdjustment));
        return new LoanPricing(
                grade, monthlyIncome, debtServiceToIncome, pricingPd, policyRate, baseMargin, expectedLoss, capital,
                spreadAdjustment, rate);
    }

    private static BigDecimal annualRate(BigDecimal rawRate) {
        return rawRate.max(BigDecimal.ZERO).setScale(ANNUAL_RATE_SCALE, RoundingMode.HALF_UP);
    }
}
