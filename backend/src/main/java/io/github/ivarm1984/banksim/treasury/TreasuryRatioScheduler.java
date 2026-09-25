package io.github.ivarm1984.banksim.treasury;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import io.github.ivarm1984.banksim.clock.DayRolledOverEvent;
import io.github.ivarm1984.banksim.statement.StatementGenerationBatchCompletedEvent;

/**
 * Drives treasury's own daily batch reactions: central bank facility
 * interest accrual on day rollover (alongside, not chained after, customer
 * interest accrual - see {@code InterestAccrualScheduler}), then - once
 * every account's statement has been generated (chained, mirrors interest ->
 * statement chaining, see TODO M4/M6.4) - the day's ratio snapshot and the
 * feedback loop that reacts to it (auto-borrow/repay, loan-origination
 * throttle - see TODO M6.5).
 */
@Service
public class TreasuryRatioScheduler {

    private static final Logger log = LoggerFactory.getLogger(TreasuryRatioScheduler.class);

    private final TreasuryService treasuryService;

    public TreasuryRatioScheduler(TreasuryService treasuryService) {
        this.treasuryService = treasuryService;
    }

    @EventListener
    @Order(DayRolledOverEvent.ORDER_TREASURY_BORROWING_INTEREST)
    public void onDayRolledOver(DayRolledOverEvent event) {
        try {
            treasuryService.accrueBorrowingInterest(event.newDate());
        } catch (Exception e) {
            log.warn("Central bank facility interest accrual failed for {}", event.newDate(), e);
        }
    }

    @EventListener
    public void onStatementGenerationBatchCompleted(StatementGenerationBatchCompletedEvent event) {
        try {
            TreasuryRatioSnapshot snapshot = treasuryService.computeAndPersist(event.date());
            treasuryService.applyFeedback(snapshot);
        } catch (Exception e) {
            log.warn("Treasury ratio snapshot failed for {}", event.date(), e);
        }
    }
}
