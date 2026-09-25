package io.github.ivarm1984.banksim.loan;

import java.math.BigDecimal;

import io.github.ivarm1984.banksim.customer.CreditGrade;

/**
 * How a loan was underwritten and priced at origination - see
 * {@link RiskBasedPricing}. {@code annualRate} = {@code policyRate} +
 * {@code baseMargin} + {@code expectedLossSpread} + {@code capitalSpread} +
 * {@code spreadAdjustment}, floored at zero and rounded to a basis point.
 */
public record LoanPricing(
        CreditGrade creditGrade,
        BigDecimal monthlyIncome,
        BigDecimal debtServiceToIncome,
        BigDecimal pricingProbabilityOfDefault,
        BigDecimal policyRate,
        BigDecimal baseMargin,
        BigDecimal expectedLossSpread,
        BigDecimal capitalSpread,
        BigDecimal spreadAdjustment,
        BigDecimal annualRate) {
}
