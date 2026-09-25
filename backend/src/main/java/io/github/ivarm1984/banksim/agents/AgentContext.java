package io.github.ivarm1984.banksim.agents;

import io.github.ivarm1984.banksim.account.AccountService;
import io.github.ivarm1984.banksim.event.DomainEventPublisher;
import io.github.ivarm1984.banksim.eventinjector.RecessionShockService;
import io.github.ivarm1984.banksim.loan.LoanService;
import io.github.ivarm1984.banksim.transaction.TransactionService;

/** Services an {@link Agent} needs to act on each tick. */
public record AgentContext(
        TransactionService transactionService,
        AccountService accountService,
        DomainEventPublisher events,
        LoanService loanService,
        RecessionShockService recessionShockService) {
}
