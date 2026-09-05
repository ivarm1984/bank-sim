package io.github.ivarm1984.banksim.agents;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import io.github.ivarm1984.banksim.ledger.InsufficientFundsException;

/**
 * Withdraws a fixed bill amount from one account once a month, on
 * {@code dueDay}. Publishes {@link BillPaymentFailedEvent} instead of
 * failing the tick when funds are insufficient.
 */
public class BillPayAgent implements Agent {

    private final long accountId;
    private final BigDecimal billAmount;
    private final int dueDay;

    private LocalDate lastPaidOn;

    public BillPayAgent(long accountId, BigDecimal billAmount, int dueDay) {
        this.accountId = accountId;
        this.billAmount = billAmount;
        this.dueDay = dueDay;
    }

    @Override
    public void onTick(LocalDateTime simulatedNow, AgentContext context) {
        LocalDate today = simulatedNow.toLocalDate();
        if (today.equals(lastPaidOn) || today.getDayOfMonth() != dueDay) {
            return;
        }
        try {
            context.transactionService().withdraw(accountId, billAmount);
        } catch (InsufficientFundsException e) {
            context.events().publish(new BillPaymentFailedEvent(accountId, billAmount, simulatedNow));
        }
        lastPaidOn = today;
    }
}
