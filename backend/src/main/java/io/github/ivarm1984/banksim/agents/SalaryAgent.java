package io.github.ivarm1984.banksim.agents;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Deposits a fixed salary into one account once a month, on {@code payday}. */
public class SalaryAgent implements Agent {

    private final long accountId;
    private final BigDecimal salaryAmount;
    private final int payday;

    private LocalDate lastPaidOn;

    public SalaryAgent(long accountId, BigDecimal salaryAmount, int payday) {
        this.accountId = accountId;
        this.salaryAmount = salaryAmount;
        this.payday = payday;
    }

    @Override
    public void onTick(LocalDateTime simulatedNow, AgentContext context) {
        LocalDate today = simulatedNow.toLocalDate();
        if (today.equals(lastPaidOn) || today.getDayOfMonth() != payday) {
            return;
        }
        context.transactionService().deposit(accountId, salaryAmount);
        lastPaidOn = today;
    }
}
