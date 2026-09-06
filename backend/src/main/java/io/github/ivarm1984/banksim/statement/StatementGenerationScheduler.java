package io.github.ivarm1984.banksim.statement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import io.github.ivarm1984.banksim.account.Account;
import io.github.ivarm1984.banksim.account.AccountService;
import io.github.ivarm1984.banksim.event.DomainEventPublisher;
import io.github.ivarm1984.banksim.interest.InterestAccrualBatchCompletedEvent;

/**
 * Drives {@link StatementGenerationService} over every account once the
 * day's interest accrual batch has completed (chained, not a parallel
 * listener on the same clock event - see TODO M4). Each account is isolated
 * in its own try/catch so one failing account doesn't stop the batch.
 */
@Service
public class StatementGenerationScheduler {

    private static final Logger log = LoggerFactory.getLogger(StatementGenerationScheduler.class);

    private final AccountService accountService;
    private final StatementGenerationService statementGenerationService;
    private final DomainEventPublisher events;

    public StatementGenerationScheduler(
            AccountService accountService, StatementGenerationService statementGenerationService, DomainEventPublisher events) {
        this.accountService = accountService;
        this.statementGenerationService = statementGenerationService;
        this.events = events;
    }

    @EventListener
    public void onInterestAccrualBatchCompleted(InterestAccrualBatchCompletedEvent event) {
        for (Account account : accountService.findAll()) {
            try {
                statementGenerationService.generateForAccount(account.id(), event.date());
            } catch (Exception e) {
                log.warn("Statement generation failed for account {} on {}", account.id(), event.date(), e);
            }
        }
        events.publish(new StatementGenerationBatchCompletedEvent(event.date()));
    }
}
