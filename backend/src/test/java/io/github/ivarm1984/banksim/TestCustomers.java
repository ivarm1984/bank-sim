package io.github.ivarm1984.banksim;

import java.math.BigDecimal;

import io.github.ivarm1984.banksim.customer.CreditGrade;
import io.github.ivarm1984.banksim.customer.NewCustomer;

/** Customer profiles for tests that exercise loans without wanting underwriting to get in the way. */
public final class TestCustomers {

    private TestCustomers() {
    }

    /**
     * An average-grade (C) borrower with an income so high no test loan
     * moves their debt service to income off the lowest band - their PD is
     * exactly the product's baseline, and they're never declined.
     */
    public static NewCustomer affluent(String fullName) {
        return new NewCustomer(fullName, CreditGrade.C, new BigDecimal("1000000.00"));
    }
}
