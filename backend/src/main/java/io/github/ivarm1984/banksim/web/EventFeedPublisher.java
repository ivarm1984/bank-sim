package io.github.ivarm1984.banksim.web;

import java.time.OffsetDateTime;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import io.github.ivarm1984.banksim.agents.BillPaymentFailedEvent;
import io.github.ivarm1984.banksim.clock.DayRolledOverEvent;
import io.github.ivarm1984.banksim.interest.InterestAccrualBatchCompletedEvent;
import io.github.ivarm1984.banksim.loan.LoanOriginatedEvent;
import io.github.ivarm1984.banksim.loan.LoanRepaidEvent;
import io.github.ivarm1984.banksim.transaction.TransactionCompletedEvent;

/**
 * Bridges domain events onto {@code /topic/events} for the dashboard's live
 * event feed. {@code fallbackExecution = true} is required: only
 * {@link TransactionCompletedEvent} is published from inside a Spring
 * transaction (so it can wait for {@code AFTER_COMMIT}) - the clock/agent/
 * interest events are published outside of one, and without the fallback
 * flag those listeners would silently never run.
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

    private void send(String type, Object payload) {
        messagingTemplate.convertAndSend("/topic/events", new EventFeedMessage(type, payload, OffsetDateTime.now()));
    }
}
