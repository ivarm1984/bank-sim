package io.github.ivarm1984.banksim.agents;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.github.ivarm1984.banksim.PostgresIntegrationTest;
import io.github.ivarm1984.banksim.account.Account;
import io.github.ivarm1984.banksim.account.AccountService;
import io.github.ivarm1984.banksim.account.AccountType;
import io.github.ivarm1984.banksim.customer.CustomerService;
import io.github.ivarm1984.banksim.event.DomainEventPublisher;
import io.github.ivarm1984.banksim.eventinjector.RecessionShockService;
import io.github.ivarm1984.banksim.loan.LoanAccount;
import io.github.ivarm1984.banksim.loan.LoanService;
import io.github.ivarm1984.banksim.loan.LoanStatus;
import io.github.ivarm1984.banksim.loan.LoanType;
import io.github.ivarm1984.banksim.transaction.TransactionService;

class BorrowerAgentTest extends PostgresIntegrationTest {

    @Autowired
    private TransactionService transactionService;
    @Autowired
    private AccountService accountService;
    @Autowired
    private CustomerService customerService;
    @Autowired
    private DomainEventPublisher events;
    @Autowired
    private LoanService loanService;
    @Autowired
    private RecessionShockService recessionShockService;

    /** A roll above every probability the agent checks - for the distress generator, "never enters distress". */
    private static final double NEVER = 0.999999;

    /** A Random whose every roll succeeds (or fails), regardless of the probability checked against it. */
    private static Random alwaysRolls(double value) {
        return new Random() {
            @Override
            public double nextDouble() {
                return value;
            }
        };
    }

    private Account openAccount() {
        var customer = customerService.create("Test Borrower");
        return accountService.open(customer.id(), AccountType.CHECKING);
    }

    @Test
    void takesAMortgageAConsumerLoanAndABusinessLoanOnASuccessfulRollAndNeverTakesASecondMortgage() {
        Account account = openAccount();
        AgentContext context = new AgentContext(transactionService, accountService, events, loanService, recessionShockService);
        BorrowerAgent agent = new BorrowerAgent(account.customerId(), account.id(), alwaysRolls(0.0), alwaysRolls(NEVER));

        agent.onTick(LocalDateTime.of(2026, 1, 1, 9, 0), context);
        agent.onTick(LocalDateTime.of(2026, 1, 2, 9, 0), context);

        List<LoanAccount> loans = loanService.findByAccountId(account.id());
        assertThat(loans).extracting(LoanAccount::loanType)
                .containsExactlyInAnyOrder(LoanType.MORTGAGE, LoanType.CONSUMER, LoanType.BUSINESS);
        assertThat(loans.stream().filter(l -> l.loanType() == LoanType.MORTGAGE)).hasSize(1);
    }

    /** A Random whose every roll succeeds and every random-index pick lands on index 0 (shortest term, minimum principal). */
    private static Random alwaysRollsAndPicksTheShortestTerm() {
        return new Random() {
            @Override
            public double nextDouble() {
                return 0.0;
            }

            @Override
            public int nextInt(int bound) {
                return 0;
            }
        };
    }

    @Test
    void businessLoanSlotFreesUpOncePaidOffInFullAllowingAnotherToBeTaken() {
        Account account = openAccount();
        AgentContext context = new AgentContext(transactionService, accountService, events, loanService, recessionShockService);
        BorrowerAgent agent = new BorrowerAgent(account.customerId(), account.id(), alwaysRollsAndPicksTheShortestTerm(), alwaysRolls(NEVER));
        transactionService.deposit(account.id(), new BigDecimal("50000.00"));

        LocalDateTime tick = LocalDateTime.of(2026, 1, 1, 9, 0);
        agent.onTick(tick, context);
        LoanAccount firstBusinessLoan = loanService.findByAccountId(account.id()).stream()
                .filter(l -> l.loanType() == LoanType.BUSINESS).findFirst().orElseThrow();
        assertThat(firstBusinessLoan.termMonths()).isEqualTo(24);

        for (int month = 1; month <= firstBusinessLoan.termMonths(); month++) {
            tick = tick.plusMonths(1);
            agent.onTick(tick, context);
        }

        LoanAccount paidOff = loanService.findById(firstBusinessLoan.id());
        assertThat(paidOff.status()).isEqualTo(LoanStatus.PAID_OFF);
        assertThat(paidOff.outstandingPrincipal()).isEqualByComparingTo(BigDecimal.ZERO);

        List<LoanAccount> businessLoans = loanService.findByAccountId(account.id()).stream()
                .filter(l -> l.loanType() == LoanType.BUSINESS).toList();
        assertThat(businessLoans).hasSize(2);
    }

    @Test
    void paysTheDueInstallmentOnTheDueDayReducingOutstandingPrincipal() {
        Account account = openAccount();
        AgentContext context = new AgentContext(transactionService, accountService, events, loanService, recessionShockService);
        BorrowerAgent agent = new BorrowerAgent(account.customerId(), account.id(), alwaysRolls(0.0), alwaysRolls(NEVER));

        agent.onTick(LocalDateTime.of(2026, 1, 1, 9, 0), context);
        LoanAccount consumerLoan = loanService.findByAccountId(account.id()).stream()
                .filter(l -> l.loanType() == LoanType.CONSUMER).findFirst().orElseThrow();
        BigDecimal principalBefore = consumerLoan.outstandingPrincipal();
        transactionService.deposit(account.id(), new BigDecimal("10000.00"));

        agent.onTick(LocalDateTime.of(2026, 2, 1, 9, 0), context);

        LoanAccount reloaded = loanService.findById(consumerLoan.id());
        assertThat(reloaded.outstandingPrincipal()).isLessThan(principalBefore);
    }

    @Test
    void insufficientFundsOnTheDueDayIsSkippedSilentlyAndLeavesTheLoanActive() {
        Account account = openAccount();
        AgentContext context = new AgentContext(transactionService, accountService, events, loanService, recessionShockService);
        BorrowerAgent agent = new BorrowerAgent(account.customerId(), account.id(), alwaysRolls(0.0), alwaysRolls(NEVER));

        agent.onTick(LocalDateTime.of(2026, 1, 1, 9, 0), context);
        LoanAccount consumerLoan = loanService.findByAccountId(account.id()).stream()
                .filter(l -> l.loanType() == LoanType.CONSUMER).findFirst().orElseThrow();
        // Spend the disbursed principal away so the due-day repayment can't be covered.
        transactionService.withdraw(account.id(), accountService.balanceOf(account.id()));

        agent.onTick(LocalDateTime.of(2026, 2, 1, 9, 0), context);

        LoanAccount reloaded = loanService.findById(consumerLoan.id());
        assertThat(reloaded.status()).isEqualTo(LoanStatus.ACTIVE);
        assertThat(reloaded.outstandingPrincipal()).isEqualByComparingTo(consumerLoan.outstandingPrincipal());
    }

    /**
     * A distressed borrower stops paying, so the installment goes overdue;
     * once distress ends the arrears are caught up, oldest first, in one go.
     */
    @Test
    void aDistressedBorrowerMissesInstallmentsAndCatchesUpOnceRecovered() {
        Account account = openAccount();
        AgentContext context = new AgentContext(transactionService, accountService, events, loanService, recessionShockService);
        double[] distressRoll = {NEVER};
        Random distress = new Random() {
            @Override
            public double nextDouble() {
                return distressRoll[0];
            }
        };
        BorrowerAgent agent = new BorrowerAgent(account.customerId(), account.id(), alwaysRolls(0.0), distress);
        agent.onTick(LocalDateTime.of(2026, 1, 1, 9, 0), context);
        LoanAccount consumerLoan = loanService.findByAccountId(account.id()).stream()
                .filter(l -> l.loanType() == LoanType.CONSUMER).findFirst().orElseThrow();
        transactionService.deposit(account.id(), new BigDecimal("10000.00"));

        // Enters distress (roll 0.0 is under the entry probability) and stays in it
        // (0.0 is also under the recovery probability, so switch to NEVER to stay).
        distressRoll[0] = 0.0;
        agent.onTick(LocalDateTime.of(2026, 1, 20, 9, 0), context);
        distressRoll[0] = NEVER;
        agent.onTick(LocalDateTime.of(2026, 2, 1, 9, 0), context);
        agent.onTick(LocalDateTime.of(2026, 3, 1, 9, 0), context);
        assertThat(loanService.findById(consumerLoan.id()).nextInstallmentNumber()).isEqualTo(1);

        // Recovers (a roll under the recovery probability), then pays both overdue installments.
        distressRoll[0] = 0.0;
        agent.onTick(LocalDateTime.of(2026, 3, 2, 9, 0), context);
        assertThat(loanService.findById(consumerLoan.id()).nextInstallmentNumber()).isEqualTo(3);
    }
}
