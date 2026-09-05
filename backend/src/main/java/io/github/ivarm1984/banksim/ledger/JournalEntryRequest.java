package io.github.ivarm1984.banksim.ledger;

import java.util.List;

public record JournalEntryRequest(String description, List<LedgerLineRequest> lines) {
}
