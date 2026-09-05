package io.github.ivarm1984.banksim.ledger;

import java.math.BigDecimal;

public record LedgerLineRequest(long ledgerAccountId, EntryType entryType, BigDecimal amount) {
}
