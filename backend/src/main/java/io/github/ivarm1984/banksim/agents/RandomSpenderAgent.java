package io.github.ivarm1984.banksim.agents;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Random;

import io.github.ivarm1984.banksim.ledger.InsufficientFundsException;

/**
 * Once a day, has a chance of spending a random amount out of one account.
 * Uses a seeded {@link Random} so runs are reproducible.
 */
public class RandomSpenderAgent implements Agent {

    private final long accountId;
    private final Random random;
    private final double dailySpendProbability;
    private final BigDecimal minSpend;
    private final BigDecimal maxSpend;

    private LocalDate lastActedOn;

    public RandomSpenderAgent(long accountId, long randomSeed, double dailySpendProbability, BigDecimal minSpend, BigDecimal maxSpend) {
        this.accountId = accountId;
        this.random = new Random(randomSeed);
        this.dailySpendProbability = dailySpendProbability;
        this.minSpend = minSpend;
        this.maxSpend = maxSpend;
    }

    @Override
    public void onTick(LocalDateTime simulatedNow, AgentContext context) {
        LocalDate today = simulatedNow.toLocalDate();
        if (today.equals(lastActedOn)) {
            return;
        }
        lastActedOn = today;
        if (random.nextDouble() >= dailySpendProbability) {
            return;
        }
        BigDecimal amount = randomAmountBetween(minSpend, maxSpend);
        try {
            context.transactionService().withdraw(accountId, amount);
        } catch (InsufficientFundsException e) {
            // Spender just skips this day if it can't cover the amount.
        }
    }

    private BigDecimal randomAmountBetween(BigDecimal min, BigDecimal max) {
        BigDecimal range = max.subtract(min);
        BigDecimal fraction = BigDecimal.valueOf(random.nextDouble());
        return min.add(range.multiply(fraction)).setScale(2, java.math.RoundingMode.HALF_UP);
    }
}
