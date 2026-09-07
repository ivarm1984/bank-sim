package io.github.ivarm1984.banksim.statement;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import io.github.ivarm1984.banksim.account.Account;
import io.github.ivarm1984.banksim.account.AccountService;
import io.github.ivarm1984.banksim.event.DomainEventPublisher;
import io.github.ivarm1984.banksim.interest.InterestAccrualBatchCompletedEvent;

/**
 * Drives {@link StatementGenerationService} over every account once the day's interest
 * accrual batch has completed (chained, not a parallel listener on the same clock event
 * - see TODO M4), in chunks of {@link #CHUNK_SIZE} accounts per transaction rather than
 * one account or one giant transaction for the whole day - see
 * {@code InterestAccrualScheduler} for why. Each chunk is isolated in its own try/catch
 * so one failing chunk doesn't stop the others.
 */
@Service
public class StatementGenerationScheduler {

    private static final Logger log = LoggerFactory.getLogger(StatementGenerationScheduler.class);
    private static final int CHUNK_SIZE = 200;

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
        for (List<Account> chunk : partition(accountService.findAll(), CHUNK_SIZE)) {
            try {
                statementGenerationService.generateForChunk(chunk, event.date());
            } catch (Exception e) {
                log.warn("Statement generation failed for chunk starting at account {} on {}", chunk.get(0).id(), event.date(), e);
            }
        }
        events.publish(new StatementGenerationBatchCompletedEvent(event.date()));
    }

    private static <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> chunks = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            chunks.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return chunks;
    }
}
