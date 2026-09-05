package io.github.ivarm1984.banksim.ledger;

import java.math.BigDecimal;

public class InsufficientFundsException extends RuntimeException {

    public InsufficientFundsException(long accountId, BigDecimal shortfallBalance) {
        super("Account %d would go negative (resulting balance %s)".formatted(accountId, shortfallBalance));
    }
}
