package io.github.ivarm1984.banksim.web;

import java.time.OffsetDateTime;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import io.github.ivarm1984.banksim.agents.BillPaymentFailedEvent;
import io.github.ivarm1984.banksim.bankhealth.BankHealthUpdatedEvent;
import io.github.ivarm1984.banksim.clock.DayRolledOverEvent;
import io.github.ivarm1984.banksim.eventinjector.RateShockTriggeredEvent;
import io.github.ivarm1984.banksim.eventinjector.RecessionEndedEvent;
import io.github.ivarm1984.banksim.eventinjector.RecessionStartedEvent;
import io.github.ivarm1984.banksim.interest.InterestAccrualBatchCompletedEvent;
import io.github.ivarm1984.banksim.loan.LoanOriginatedEvent;
import io.github.ivarm1984.banksim.loan.LoanPhaseChangedEvent;
import io.github.ivarm1984.banksim.loan.LoanRepaidEvent;
import io.github.ivarm1984.banksim.transaction.TransactionCompletedEvent;
import io.github.ivarm1984.banksim.treasury.TreasuryRatiosUpdatedEvent;

/**
 * Bridges domain events onto {@code /topic/events} for the dashboard's live
 * event feed. {@code fallbackExecution = true} is required:
 * some events (transactions, treasury ratios, bank health, loan events) are
 * published from inside a Spring transaction (so they wait for
 * {@code AFTER_COMMIT}) - the clock/agent/interest events are published
 * outside of one, and without the fallback flag those listeners would
 * silently never run.
 */
@Component
public class EventFeedPublisher {

    private final SimpMessagingTemplate messagingTemplate;

    public EventFeedPublisher(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onTransactionCompleted(TransactionCompletedEvent event) {
        send("TRANSACTION_COMPLETED", event);
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onBillPaymentFailed(BillPaymentFailedEvent event) {
        send("BILL_PAYMENT_FAILED", event);
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onInterestAccrualBatchCompleted(InterestAccrualBatchCompletedEvent event) {
        send("INTEREST_ACCRUAL_BATCH_COMPLETED", event);
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onDayRolledOver(DayRolledOverEvent event) {
        send("DAY_ROLLED_OVER", event);
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onLoanOriginated(LoanOriginatedEvent event) {
        send("LOAN_ORIGINATED", event);
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onLoanRepaid(LoanRepaidEvent event) {
        send("LOAN_REPAID", event);
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRateShockTriggered(RateShockTriggeredEvent event) {
        send("RATE_SHOCK_TRIGGERED", event);
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRecessionStarted(RecessionStartedEvent event) {
        send("RECESSION_STARTED", event);
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRecessionEnded(RecessionEndedEvent event) {
        send("RECESSION_ENDED", event);
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onLoanPhaseChanged(LoanPhaseChangedEvent event) {
        send("LOAN_PHASE_CHANGED", event);
    }

    /**
     * The end of the EOD chain's treasury step - unlike {@code DAY_ROLLED_OVER}
     * (sent as soon as the clock crosses midnight, while interest -> statements
     * -> treasury -> bank health are still running synchronously), a reload
     * off this sees the day's committed snapshot.
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onTreasuryRatiosUpdated(TreasuryRatiosUpdatedEvent event) {
        send("TREASURY_RATIOS_UPDATED", event);
    }

    /** Last step of the EOD chain - see {@link #onTreasuryRatiosUpdated}. */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onBankHealthUpdated(BankHealthUpdatedEvent event) {
        send("BANK_HEALTH_UPDATED", event);
    }

    private void send(String type, Object payload) {
        messagingTemplate.convertAndSend("/topic/events", new EventFeedMessage(type, payload, OffsetDateTime.now()));
    }
}
