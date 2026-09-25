package io.github.ivarm1984.banksim.loan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import io.github.ivarm1984.banksim.customer.CreditGrade;
import io.github.ivarm1984.banksim.treasury.TreasuryService;

/** Plain unit test (no Spring/Postgres) for {@link RiskBasedPricing}'s rate build-up. */
class RiskBasedPricingTest {

    private static final BigDecimal POLICY_RATE = new BigDecimal("0.03");
    private static final BigDecimal OCR = TreasuryService.OVERALL_CAPITAL_REQUIREMENT;
    private static final BigDecimal LOW_DSTI = new BigDecimal("0.10");
    private static final BigDecimal INCOME = new BigDecimal("3000.00");

    private static LoanPricing averageBorrower(LoanType type) {
        return RiskBasedPricing.price(
                type, CreditGrade.C, INCOME, LOW_DSTI, CreditRisk.productDefaultProbability(type), POLICY_RATE, OCR, BigDecimal.ZERO);
    }

    /** The base margins were calibrated so an average borrower pays about the flat spreads this replaced (1.5% / 9% / 5%). */
    @Test
    void anAverageBorrowerPaysRoughlyTheOldFlatSpreadPerProduct() {
        assertThat(averageBorrower(LoanType.MORTGAGE).annualRate().subtract(POLICY_RATE).doubleValue()).isCloseTo(0.015, within(0.0005));
        assertThat(averageBorrower(LoanType.CONSUMER).annualRate().subtract(POLICY_RATE).doubleValue()).isCloseTo(0.090, within(0.0005));
        assertThat(averageBorrower(LoanType.BUSINESS).annualRate().subtract(POLICY_RATE).doubleValue()).isCloseTo(0.050, within(0.0005));
    }

    @Test
    void expectedLossIsPdTimesLgd() {
        assertThat(RiskBasedPricing.expectedLossSpread(LoanType.CONSUMER, new BigDecimal("0.10"))).isEqualByComparingTo("0.06");
        assertThat(RiskBasedPricing.expectedLossSpread(LoanType.MORTGAGE, new BigDecimal("0.10"))).isEqualByComparingTo("0.015");
    }

    @Test
    void theCapitalChargeFollowsRiskWeightAndTheCapitalTheBankTargets() {
        // 35% / 75% / 100% risk weight x 12.5% OCR x 10% hurdle.
        assertThat(RiskBasedPricing.capitalSpread(LoanType.MORTGAGE, OCR)).isEqualByComparingTo("0.004375");
        assertThat(RiskBasedPricing.capitalSpread(LoanType.CONSUMER, OCR)).isEqualByComparingTo("0.009375");
        assertThat(RiskBasedPricing.capitalSpread(LoanType.BUSINESS, OCR)).isEqualByComparingTo("0.012500");
        // A 5% management buffer on top: 100% x 17.5% x 10%.
        assertThat(RiskBasedPricing.capitalSpread(LoanType.BUSINESS, OCR.add(new BigDecimal("0.05")))).isEqualByComparingTo("0.017500");
    }

    @Test
    void theRateIsTheSumOfItsPartsRoundedToABasisPointAndFlooredAtZero() {
        LoanPricing pricing = RiskBasedPricing.price(
                LoanType.BUSINESS, CreditGrade.D, INCOME, LOW_DSTI, new BigDecimal("0.027"), POLICY_RATE, OCR, new BigDecimal("0.01"));
        // 3% + 3.08% + 2.7% x 45% + 1.25% + 1% = 9.545% -> 9.55%
        assertThat(pricing.expectedLossSpread()).isEqualByComparingTo("0.01215");
        assertThat(pricing.annualRate()).isEqualByComparingTo("0.0955");

        LoanPricing negative = RiskBasedPricing.price(
                LoanType.MORTGAGE, CreditGrade.A, INCOME, LOW_DSTI, new BigDecimal("0.002"), BigDecimal.ZERO, OCR, new BigDecimal("-0.05"));
        assertThat(negative.annualRate()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void affordabilityIsAssessedAtTheRateBeforeTheExpectedLoss() {
        BigDecimal beforeCreditRisk = RiskBasedPricing.rateBeforeCreditRisk(LoanType.CONSUMER, POLICY_RATE, OCR, BigDecimal.ZERO);
        // 3% + 6.56% + 0.9375% = 10.4975% -> 10.50%
        assertThat(beforeCreditRisk).isEqualByComparingTo("0.1050");
        assertThat(averageBorrower(LoanType.CONSUMER).annualRate()).isGreaterThan(beforeCreditRisk);
    }
}
