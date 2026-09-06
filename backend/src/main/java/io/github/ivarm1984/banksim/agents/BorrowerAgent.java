package io.github.ivarm1984.banksim.agents;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Random;

import io.github.ivarm1984.banksim.loan.LoanAccount;
import io.github.ivarm1984.banksim.loan.LoanType;

/**
 * Takes out at most one mortgage (ever) and, independently, a consumer loan
 * whenever it doesn't currently have one active - each on a random daily
 * roll, like {@link RandomSpenderAgent} - then pays the fixed monthly
 * installment on the due day, like {@link BillPayAgent}. A missed payment
 * (insufficient funds) is silently skipped, same as {@link RandomSpenderAgent}'s
 * spend-skip - delinquency/NPL tracking is deliberately out of scope here.
 */
public class BorrowerAgent implements Agent {

    private static final double MORTGAGE_DAILY_PROBABILITY = 0.0005;
    private static final double CONSUMER_LOAN_DAILY_PROBABILITY = 0.01;

    private static final BigDecimal MORTGAGE_MIN_PRINCIPAL = new BigDecimal("80000.00");
    private static final BigDecimal MORTGAGE_MAX_PRINCIPAL = new BigDecimal("350000.00");
    private static final int[] MORTGAGE_TERM_MONTHS = {180, 240, 300, 360};

    private static final BigDecimal CONSUMER_MIN_PRINCIPAL = new BigDecimal("1000.00");
    private static final BigDecimal CONSUMER_MAX_PRINCIPAL = new BigDecimal("15000.00");
    private static final int[] CONSUMER_TERM_MONTHS = {12, 24, 36};

    private final long customerId;
    private final long accountId;
    private final Random random;

    private boolean hasHadMortgage;
    private Long mortgageLoanId;
    private BigDecimal mortgageInstallmentAmount;
    private int mortgageDueDay;
    private LocalDate mortgageLastPaidOn;

    private Long consumerLoanId;
    private BigDecimal consumerInstallmentAmount;
    private int consumerDueDay;
    private LocalDate consumerLastPaidOn;

    private LocalDate lastActedOn;

    public BorrowerAgent(long customerId, long accountId, long randomSeed) {
        this(customerId, accountId, new Random(randomSeed));
    }

    /** Test seam - lets a test script exact roll outcomes instead of hunting for a seed. */
    BorrowerAgent(long customerId, long accountId, Random random) {
        this.customerId = customerId;
        this.accountId = accountId;
        this.random = random;
    }

    @Override
    public void onTick(LocalDateTime simulatedNow, AgentContext context) {
        LocalDate today = simulatedNow.toLocalDate();
        payDueInstallments(today, context);

        if (today.equals(lastActedOn)) {
            return;
        }
        lastActedOn = today;
        maybeTakeMortgage(context);
        maybeTakeConsumerLoan(context);
    }

    private void payDueInstallments(LocalDate today, AgentContext context) {
        if (mortgageLoanId != null && today.getDayOfMonth() == mortgageDueDay && !today.equals(mortgageLastPaidOn)) {
            pay(mortgageLoanId, mortgageInstallmentAmount, context);
            mortgageLastPaidOn = today;
        }
        if (consumerLoanId != null && today.getDayOfMonth() == consumerDueDay && !today.equals(consumerLastPaidOn)) {
            if (pay(consumerLoanId, consumerInstallmentAmount, context)) {
                consumerLoanId = null;
            }
            consumerLastPaidOn = today;
        }
    }

    /** @return true if the loan is now fully paid off. */
    private boolean pay(long loanId, BigDecimal amount, AgentContext context) {
        try {
            return context.loanService().repay(loanId, amount).outstandingPrincipalAfter().signum() == 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void maybeTakeMortgage(AgentContext context) {
        if (hasHadMortgage || random.nextDouble() >= MORTGAGE_DAILY_PROBABILITY) {
            return;
        }
        BigDecimal principal = randomAmountBetween(MORTGAGE_MIN_PRINCIPAL, MORTGAGE_MAX_PRINCIPAL);
        int termMonths = MORTGAGE_TERM_MONTHS[random.nextInt(MORTGAGE_TERM_MONTHS.length)];
        try {
            LoanAccount loan = context.loanService()
                    .originateAndDisburse(customerId, accountId, LoanType.MORTGAGE, principal, termMonths);
            mortgageLoanId = loan.id();
            mortgageInstallmentAmount = loan.installmentAmount();
            mortgageDueDay = loan.originationDate().getDayOfMonth();
            hasHadMortgage = true;
        } catch (RuntimeException e) {
            // Throttled or otherwise rejected - roll again another day.
        }
    }

    private void maybeTakeConsumerLoan(AgentContext context) {
        if (consumerLoanId != null || random.nextDouble() >= CONSUMER_LOAN_DAILY_PROBABILITY) {
            return;
        }
        BigDecimal principal = randomAmountBetween(CONSUMER_MIN_PRINCIPAL, CONSUMER_MAX_PRINCIPAL);
        int termMonths = CONSUMER_TERM_MONTHS[random.nextInt(CONSUMER_TERM_MONTHS.length)];
        try {
            LoanAccount loan = context.loanService()
                    .originateAndDisburse(customerId, accountId, LoanType.CONSUMER, principal, termMonths);
            consumerLoanId = loan.id();
            consumerInstallmentAmount = loan.installmentAmount();
            consumerDueDay = loan.originationDate().getDayOfMonth();
        } catch (RuntimeException e) {
            // Throttled or otherwise rejected - roll again another day.
        }
    }

    private BigDecimal randomAmountBetween(BigDecimal min, BigDecimal max) {
        BigDecimal range = max.subtract(min);
        BigDecimal fraction = BigDecimal.valueOf(random.nextDouble());
        return min.add(range.multiply(fraction)).setScale(2, RoundingMode.HALF_UP);
    }
}
