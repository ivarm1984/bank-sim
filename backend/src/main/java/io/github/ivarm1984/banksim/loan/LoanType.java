package io.github.ivarm1984.banksim.loan;

/**
 * MORTGAGE: one per account, low spread, long term. CONSUMER: repeatable,
 * higher spread, short term. BUSINESS: repeatable, medium spread/term. All
 * three are staged/provisioned under IFRS 9 - see {@link CreditRisk}.
 */
public enum LoanType {
    MORTGAGE,
    CONSUMER,
    BUSINESS
}
