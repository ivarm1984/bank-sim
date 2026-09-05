package io.github.ivarm1984.banksim.account;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record Account(
        Long id,
        Long customerId,
        String accountType,
        BigDecimal currentBalance,
        OffsetDateTime createdAt) {
}
