package io.github.ivarm1984.banksim.ledger;

import java.math.BigDecimal;

public class UnbalancedJournalEntryException extends RuntimeException {

    public UnbalancedJournalEntryException(BigDecimal totalDebits, BigDecimal totalCredits) {
        super("Journal entry is unbalanced: debits=%s, credits=%s".formatted(totalDebits, totalCredits));
    }
}
