package io.github.ivarm1984.banksim.agents;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;

import io.github.ivarm1984.banksim.loan.CreditRisk;
import io.github.ivarm1984.banksim.loan.LoanAccount;
import io.github.ivarm1984.banksim.loan.LoanType;

/**
 * Takes out at most one mortgage (ever) and, independently, a consumer loan
 * and a business loan, each whenever it doesn't currently have one active -
 * each on its own random daily roll, like {@link RandomSpenderAgent} - then
 * pays the fixed monthly installment once it falls due, like
 * {@link BillPayAgent}.
 *
 * <p>Missed installments aren't forgiven: every overdue installment is
 * retried daily until paid, oldest first, so arrears (days past due, which
 * drive the loan's IFRS 9 stage - see {@code loan.LoanStagingService})
 * build up and clear from real payment behaviour. Arrears come from two
 * sources: plain insufficient funds, and <em>payment distress</em> - each
 * loan independently enters distress on a daily roll derived from its
 * product's 12-month PD (higher during a recession), stops paying while
 * distressed, and leaves distress on a daily recovery roll. A distress spell
 * outlasting ~90 days of arrears is a default; a shorter one cures.
 */
public class BorrowerAgent implements Agent {

    private static final double MORTGAGE_DAILY_PROBABILITY = 0.0005;
    private static final double CONSUMER_LOAN_DAILY_PROBABILITY = 0.01;
    private static final double BUSINESS_LOAN_DAILY_PROBABILITY = 0.002;

    /**
     * Distress starts more often than default happens - many spells cure
     * before 90 days past due - so the entry rate is this multiple of the
     * product's 12-month PD, keeping realised default rates in the same
     * ballpark as the PDs the ECL is computed with.
     */
    static final double DISTRESS_ENTRY_PD_MULTIPLE = 2.5;
    /** Mean distress spell of ~4 months. */
    static final double DISTRESS_RECOVERY_DAILY_PROBABILITY = 1.0 / 120;

    private static final BigDecimal MORTGAGE_MIN_PRINCIPAL = new BigDecimal("80000.00");
    private static final BigDecimal MORTGAGE_MAX_PRINCIPAL = new BigDecimal("350000.00");
    private static final int[] MORTGAGE_TERM_MONTHS = {180, 240, 300, 360};

    private static final BigDecimal CONSUMER_MIN_PRINCIPAL = new BigDecimal("1000.00");
    private static final BigDecimal CONSUMER_MAX_PRINCIPAL = new BigDecimal("15000.00");
    private static final int[] CONSUMER_TERM_MONTHS = {12, 24, 36};

    private static final BigDecimal BUSINESS_MIN_PRINCIPAL = new BigDecimal("20000.00");
    private static final BigDecimal BUSINESS_MAX_PRINCIPAL = new BigDecimal("200000.00");
    private static final int[] BUSINESS_TERM_MONTHS = {24, 36, 60, 84};

    private final long customerId;
    private final long accountId;
    private final Random random;
    private final Random distressRandom;

    private final LoanSlot mortgage = new LoanSlot(LoanType.MORTGAGE);
    private final LoanSlot consumer = new LoanSlot(LoanType.CONSUMER);
    private final LoanSlot business = new LoanSlot(LoanType.BUSINESS);
    private final List<LoanSlot> slots = List.of(mortgage, consumer, business);
    private boolean hasHadMortgage;

    private LocalDate lastActedOn;

    public BorrowerAgent(long customerId, long accountId, long randomSeed) {
        this(customerId, accountId, new Random(randomSeed), new Random(~randomSeed));
    }

    /**
     * Test seam - lets a test script exact roll outcomes instead of hunting
     * for a seed. Loan-taking decisions and payment-distress rolls draw from
     * separate generators, so a test can force one without the other.
     */
    BorrowerAgent(long customerId, long accountId, Random random, Random distressRandom) {
        this.customerId = customerId;
        this.accountId = accountId;
        this.random = random;
        this.distressRandom = distressRandom;
    }

    @Override
    public void onTick(LocalDateTime simulatedNow, AgentContext context) {
        LocalDate today = simulatedNow.toLocalDate();
        boolean firstTickToday = !today.equals(lastActedOn);
        lastActedOn = today;
        if (firstTickToday) {
            boolean recessionActive = context.recessionShockService().isActive(today);
            slots.forEach(slot -> slot.rollDistress(recessionActive));
        }

        slots.forEach(slot -> slot.payWhatIsDue(today, context));

        if (!firstTickToday) {
            return;
        }
        maybeTakeMortgage(context);
        maybeTakeLoan(consumer, CONSUMER_LOAN_DAILY_PROBABILITY, CONSUMER_MIN_PRINCIPAL, CONSUMER_MAX_PRINCIPAL, CONSUMER_TERM_MONTHS, context);
        maybeTakeLoan(business, BUSINESS_LOAN_DAILY_PROBABILITY, BUSINESS_MIN_PRINCIPAL, BUSINESS_MAX_PRINCIPAL, BUSINESS_TERM_MONTHS, context);
    }

    private void maybeTakeMortgage(AgentContext context) {
        if (hasHadMortgage || random.nextDouble() >= MORTGAGE_DAILY_PROBABILITY) {
            return;
        }
        BigDecimal principal = randomAmountBetween(MORTGAGE_MIN_PRINCIPAL, MORTGAGE_MAX_PRINCIPAL);
        int termMonths = MORTGAGE_TERM_MONTHS[random.nextInt(MORTGAGE_TERM_MONTHS.length)];
        if (originate(mortgage, principal, termMonths, context)) {
            hasHadMortgage = true;
        }
    }

    private void maybeTakeLoan(
            LoanSlot slot, double dailyProbability, BigDecimal minPrincipal, BigDecimal maxPrincipal, int[] terms, AgentContext context) {
        if (slot.loanId != null || random.nextDouble() >= dailyProbability) {
            return;
        }
        BigDecimal principal = randomAmountBetween(minPrincipal, maxPrincipal);
        int termMonths = terms[random.nextInt(terms.length)];
        originate(slot, principal, termMonths, context);
    }

    private boolean originate(LoanSlot slot, BigDecimal principal, int termMonths, AgentContext context) {
        try {
            LoanAccount loan = context.loanService().originateAndDisburse(customerId, accountId, slot.type, principal, termMonths);
            slot.open(loan);
            return true;
        } catch (RuntimeException e) {
            // Throttled or otherwise rejected - roll again another day.
            return false;
        }
    }

    private BigDecimal randomAmountBetween(BigDecimal min, BigDecimal max) {
        BigDecimal range = max.subtract(min);
        BigDecimal fraction = BigDecimal.valueOf(random.nextDouble());
        return min.add(range.multiply(fraction)).setScale(2, RoundingMode.HALF_UP);
    }

    /** One loan the agent may hold, with its own due-date tracking and distress state. */
    private final class LoanSlot {

        private final LoanType type;
        private Long loanId;
        private BigDecimal installmentAmount;
        /** Due date of the oldest unpaid installment - same iterative {@code plusMonths(1)} walk as the loan's own schedule. */
        private LocalDate nextDueDate;
        private LocalDate lastPaymentAttemptOn;
        private boolean distressed;

        private LoanSlot(LoanType type) {
            this.type = type;
        }

        private void open(LoanAccount loan) {
            loanId = loan.id();
            installmentAmount = loan.installmentAmount();
            nextDueDate = loan.originationDate().plusMonths(1);
            lastPaymentAttemptOn = null;
            distressed = false;
        }

        private void rollDistress(boolean recessionActive) {
            if (loanId == null) {
                return;
            }
            if (distressed) {
                distressed = distressRandom.nextDouble() >= DISTRESS_RECOVERY_DAILY_PROBABILITY;
                return;
            }
            double annualPd = CreditRisk.twelveMonthDefaultProbability(type, recessionActive).doubleValue();
            double dailyEntry = 1 - Math.pow(1 - Math.min(0.99, annualPd * DISTRESS_ENTRY_PD_MULTIPLE), 1.0 / 365);
            distressed = distressRandom.nextDouble() < dailyEntry;
        }

        /** Pays every installment due by {@code today}, oldest first, stopping at the first failure - retried tomorrow. */
        private void payWhatIsDue(LocalDate today, AgentContext context) {
            if (loanId == null || distressed || today.isBefore(nextDueDate) || today.equals(lastPaymentAttemptOn)) {
                return;
            }
            lastPaymentAttemptOn = today;
            while (loanId != null && !today.isBefore(nextDueDate)) {
                try {
                    boolean paidOff = context.loanService().repay(loanId, installmentAmount).outstandingPrincipalAfter().signum() == 0;
                    nextDueDate = nextDueDate.plusMonths(1);
                    if (paidOff) {
                        loanId = null;
                    }
                } catch (RuntimeException e) {
                    return;
                }
            }
        }
    }
}
