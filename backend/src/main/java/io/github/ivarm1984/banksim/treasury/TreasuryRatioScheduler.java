package io.github.ivarm1984.banksim.treasury;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import io.github.ivarm1984.banksim.statement.StatementGenerationBatchCompletedEvent;

/**
 * Snapshots the day's treasury ratios once every account's statement has
 * been generated (chained, not a parallel listener on the same clock event -
 * mirrors interest -> statement chaining, see TODO M4/M6.4).
 */
@Service
public class TreasuryRatioScheduler {

    private static final Logger log = LoggerFactory.getLogger(TreasuryRatioScheduler.class);

    private final TreasuryService treasuryService;

    public TreasuryRatioScheduler(TreasuryService treasuryService) {
        this.treasuryService = treasuryService;
    }

    @EventListener
    public void onStatementGenerationBatchCompleted(StatementGenerationBatchCompletedEvent event) {
        try {
            treasuryService.computeAndPersist(event.date());
        } catch (Exception e) {
            log.warn("Treasury ratio snapshot failed for {}", event.date(), e);
        }
    }
}
