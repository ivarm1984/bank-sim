package io.github.ivarm1984.banksim.ledger;

import java.time.OffsetDateTime;

public record LedgerAccount(
        Long id,
        LedgerAccountType type,
        Long accountId,
        String name,
        OffsetDateTime createdAt) {
}
