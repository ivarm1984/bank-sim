package io.github.ivarm1984.banksim.interest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import io.github.ivarm1984.banksim.account.Account;
import io.github.ivarm1984.banksim.account.AccountService;
import io.github.ivarm1984.banksim.clock.DayRolledOverEvent;
import io.github.ivarm1984.banksim.event.DomainEventPublisher;

/**
 * Drives {@link InterestAccrualService} over every account on each simulated
 * day rollover. Each account's accrual is isolated in its own try/catch (like
 * {@code AgentScheduler}) so one failing account doesn't stop the batch.
 */
@Service
public class InterestAccrualScheduler {

    private static final Logger log = LoggerFactory.getLogger(InterestAccrualScheduler.class);

    private final AccountService accountService;
    private final InterestAccrualService interestAccrualService;
    private final DomainEventPublisher events;

    public InterestAccrualScheduler(
            AccountService accountService, InterestAccrualService interestAccrualService, DomainEventPublisher events) {
        this.accountService = accountService;
        this.interestAccrualService = interestAccrualService;
        this.events = events;
    }

    @EventListener
    public void onDayRolledOver(DayRolledOverEvent event) {
        for (Account account : accountService.findAll()) {
            try {
                interestAccrualService.accrueForAccount(account.id(), event.newDate());
            } catch (Exception e) {
                log.warn("Interest accrual failed for account {} on {}", account.id(), event.newDate(), e);
            }
        }
        events.publish(new InterestAccrualBatchCompletedEvent(event.newDate()));
    }
}
