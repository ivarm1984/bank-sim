package io.github.ivarm1984.banksim.ledger;

import java.math.BigDecimal;

public record LedgerAccountBalance(
        Long id,
        LedgerAccountType type,
        Long accountId,
        Long loanId,
        String name,
        BigDecimal totalDebits,
        BigDecimal totalCredits) {
}
