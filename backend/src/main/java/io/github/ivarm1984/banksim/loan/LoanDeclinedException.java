package io.github.ivarm1984.banksim.loan;

/** Underwriting said no - the borrower can't afford the loan, or is riskier than the CEO's approval cutoff. */
public class LoanDeclinedException extends IllegalStateException {

    public LoanDeclinedException(String message) {
        super(message);
    }
}
