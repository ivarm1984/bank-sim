package io.github.ivarm1984.banksim.transaction;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record Transaction(
        Long id,
        TransactionType type,
        Long fromAccountId,
        Long toAccountId,
        BigDecimal amount,
        Long journalEntryId,
        OffsetDateTime createdAt) {
}
