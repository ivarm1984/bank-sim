package io.github.ivarm1984.banksim.transaction;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Published after a deposit/withdrawal/transfer's ledger posting has committed. */
public record TransactionCompletedEvent(
        Long transactionId,
        TransactionType type,
        Long fromAccountId,
        Long toAccountId,
        BigDecimal amount,
        OffsetDateTime createdAt) {
}
