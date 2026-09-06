package io.github.ivarm1984.banksim.loan;

public enum LoanPaymentType {
    /** Pays at most the regular installment amount for the current period. */
    NORMAL,
    /** Pays more than the regular installment but less than a full payoff - reduces outstanding principal ahead of schedule. */
    EARLY_PARTIAL,
    /** Pays off the entire remaining outstanding principal plus interest due, closing the loan. */
    EARLY_PAYOFF
}
