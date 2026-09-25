package io.github.ivarm1984.banksim.customer;

import java.math.BigDecimal;

/** A customer to create, with its credit profile - see {@link CustomerService#createBatch}. */
public record NewCustomer(String fullName, CreditGrade creditGrade, BigDecimal monthlyIncome) {
}
