package io.github.ivarm1984.banksim.bankhealth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import io.github.ivarm1984.banksim.treasury.TreasuryRatiosUpdatedEvent;

/**
 * Chains bank-health evaluation off the end of the treasury batch (see
 * TODO M6.4/M6.5) - one more listener in the same chain
 * {@code StatementGenerationBatchCompletedEvent} -> ratio snapshot ->
 * feedback loop already forms.
 */
@Service
public class BankHealthScheduler {

    private static final Logger log = LoggerFactory.getLogger(BankHealthScheduler.class);

    private final BankHealthService bankHealthService;

    public BankHealthScheduler(BankHealthService bankHealthService) {
        this.bankHealthService = bankHealthService;
    }

    @EventListener
    public void onTreasuryRatiosUpdated(TreasuryRatiosUpdatedEvent event) {
        try {
            bankHealthService.computeAndPersist(event);
        } catch (Exception e) {
            log.warn("Bank health evaluation failed for {}", event.date(), e);
        }
    }
}
