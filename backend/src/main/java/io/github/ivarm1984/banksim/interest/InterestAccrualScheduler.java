package io.github.ivarm1984.banksim.interest;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import io.github.ivarm1984.banksim.account.Account;
import io.github.ivarm1984.banksim.account.AccountService;
import io.github.ivarm1984.banksim.clock.DayRolledOverEvent;
import io.github.ivarm1984.banksim.event.DomainEventPublisher;

/**
 * Drives {@link InterestAccrualService} over every account on each simulated day
 * rollover, in chunks of {@link #CHUNK_SIZE} accounts per transaction rather than one
 * account or one giant transaction for the whole day - one bulk INSERT/UPDATE per chunk
 * instead of one round trip per account (~3-4 minutes/day at 20,000 accounts before this
 * batching - see TODO.md "Later phases"). Each chunk is isolated in its own try/catch
 * (like {@code AgentScheduler}) so one failing chunk doesn't stop the others.
 */
@Service
public class InterestAccrualScheduler {

    private static final Logger log = LoggerFactory.getLogger(InterestAccrualScheduler.class);
    private static final int CHUNK_SIZE = 200;

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
        var ratesByType = interestAccrualService.currentRates();
        for (List<Account> chunk : partition(accountService.findAll(), CHUNK_SIZE)) {
            try {
                interestAccrualService.accrueForChunk(chunk, ratesByType, event.newDate());
            } catch (Exception e) {
                log.warn("Interest accrual failed for chunk starting at account {} on {}", chunk.get(0).id(), event.newDate(), e);
            }
        }
        events.publish(new InterestAccrualBatchCompletedEvent(event.newDate()));
    }

    private static <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> chunks = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            chunks.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return chunks;
    }
}
