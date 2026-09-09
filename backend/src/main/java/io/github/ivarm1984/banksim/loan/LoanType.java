package io.github.ivarm1984.banksim.loan;

/**
 * MORTGAGE: one per account, low spread, long term. CONSUMER: repeatable,
 * higher spread, short term. BUSINESS: repeatable, medium spread/term,
 * subject to phase/provisioning tracking - see {@link LoanPhase}.
 */
public enum LoanType {
    MORTGAGE,
    CONSUMER,
    BUSINESS
}
