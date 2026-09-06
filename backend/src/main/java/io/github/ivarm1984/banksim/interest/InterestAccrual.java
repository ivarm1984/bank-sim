package io.github.ivarm1984.banksim.interest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

public record InterestAccrual(
        Long id,
        Long accountId,
        LocalDate accrualDate,
        BigDecimal principalBalance,
        BigDecimal annualRate,
        BigDecimal amount,
        Long journalEntryId,
        OffsetDateTime createdAt) {
}
