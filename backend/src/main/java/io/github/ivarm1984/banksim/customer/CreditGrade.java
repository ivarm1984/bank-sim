package io.github.ivarm1984.banksim.customer;

/**
 * The bank's internal rating of a customer, A (best) to E (worst) - in a
 * real bank the output of an application/behavioural scorecard. Assigned
 * once when the customer is created; {@code loan.CreditRisk} turns it into a
 * PD multiplier at origination.
 */
public enum CreditGrade {
    A, B, C, D, E
}
