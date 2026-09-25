package io.github.ivarm1984.banksim.customer;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record Customer(Long id, String fullName, CreditGrade creditGrade, BigDecimal monthlyIncome, OffsetDateTime createdAt) {
}
