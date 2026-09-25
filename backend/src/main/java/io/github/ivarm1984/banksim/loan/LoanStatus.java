package io.github.ivarm1984.banksim.loan;

public enum LoanStatus {
    ACTIVE,
    PAID_OFF,
    /** Defaulted and never cured - derecognised against its loss allowance, see {@link LoanWriteOffService}. */
    WRITTEN_OFF
}
