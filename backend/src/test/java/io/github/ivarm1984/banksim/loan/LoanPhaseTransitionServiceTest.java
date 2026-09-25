package io.github.ivarm1984.banksim.loan;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.ivarm1984.banksim.PostgresIntegrationTest;
import io.github.ivarm1984.banksim.account.Account;
import io.github.ivarm1984.banksim.account.AccountService;
import io.github.ivarm1984.banksim.account.AccountType;
import io.github.ivarm1984.banksim.customer.Customer;
import io.github.ivarm1984.banksim.customer.CustomerService;
import io.github.ivarm1984.banksim.event.DomainEventPublisher;
import io.github.ivarm1984.banksim.ledger.LedgerAccountService;
import io.github.ivarm1984.banksim.ledger.LedgerAccountType;
import io.github.ivarm1984.banksim.ledger.LedgerReconciliationService;
import io.github.ivarm1984.banksim.transaction.TransactionService;

/**
 * {@code LoanPhaseTransitionService.rollDailyTransitions} always targets
 * every active BUSINESS loan in the database (see
 * {@code LoanRepository.findActiveByLoanType}), including ones other test
 * classes may have originated in this shared Testcontainers container.
 * Assertions here are therefore scoped to this test's own loan row and
 * phase history (both row/FK-scoped, unaffected by unrelated loans) plus
 * the suite-wide trial-balance invariant (holds regardless of how many
 * loans transition at once) - never to aggregate singleton ledger balances,
 * which other concurrently-active BUSINESS loans could also move.
 */
class LoanPhaseTransitionServiceTest extends PostgresIntegrationTest {

    @Autowired
    private LoanService loanService;
    @Autowired
    private LoanRepository loanRepository;
    @Autowired
    private LoanProvisionPoster provisionPoster;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private LedgerAccountService ledgerAccountService;
    @Autowired
    private TransactionService transactionService;
    @Autowired
    private DomainEventPublisher events;
    @Autowired
    private AccountService accountService;
    @Autowired
    private CustomerService customerService;
    @Autowired
    private LedgerReconciliationService reconciliationService;

    /** Every roll returns {@code value}, both for the downgrade and the upgrade check. */
    private static Random alwaysRolls(double value) {
        return new Random() {
            @Override
            public double nextDouble() {
                return value;
            }
        };
    }

    private Account openAccount() {
        Customer customer = customerService.create("Test Business Borrower");
        return accountService.open(customer.id(), AccountType.CHECKING);
    }

    private LoanAccount originateBusinessLoan(BigDecimal principal) {
        Account account = openAccount();
        return loanService.originateAndDisburse(account.customerId(), account.id(), LoanType.BUSINESS, principal, 60);
    }

    @Test
    void downgradeTwiceThenRecoverTwicePostsMatchingProvisioningEntriesAndKeepsTrialBalanceZero() {
        LoanAccount loan = originateBusinessLoan(new BigDecimal("100000.00"));

        LoanPhaseTransitionService alwaysDowngrades =
                new LoanPhaseTransitionService(loanRepository, provisionPoster, events, transactionTemplate, alwaysRolls(0.0));

        alwaysDowngrades.rollDailyTransitions(LocalDate.of(2026, 6, 1), false);
        LoanAccount underperforming = loanService.findById(loan.id());
        assertThat(underperforming.phase()).isEqualTo(LoanPhase.UNDERPERFORMING);
        assertThat(underperforming.provisionAmount()).isEqualByComparingTo(new BigDecimal("10000.00"));
        assertThat(reconciliationService.trialBalance().isBalanced()).isTrue();

        alwaysDowngrades.rollDailyTransitions(LocalDate.of(2026, 6, 2), true);
        LoanAccount nonPerforming = loanService.findById(loan.id());
        assertThat(nonPerforming.phase()).isEqualTo(LoanPhase.NON_PERFORMING);
        assertThat(nonPerforming.provisionAmount()).isEqualByComparingTo(new BigDecimal("50000.00"));
        assertThat(reconciliationService.trialBalance().isBalanced()).isTrue();

        // 0.005 misses both downgrade thresholds (baseline 0.0005, recession 0.004) but hits recovery (0.01).
        LoanPhaseTransitionService alwaysRecovers =
                new LoanPhaseTransitionService(loanRepository, provisionPoster, events, transactionTemplate, alwaysRolls(0.005));

        alwaysRecovers.rollDailyTransitions(LocalDate.of(2026, 6, 3), false);
        LoanAccount backToUnderperforming = loanService.findById(loan.id());
        assertThat(backToUnderperforming.phase()).isEqualTo(LoanPhase.UNDERPERFORMING);
        assertThat(backToUnderperforming.provisionAmount()).isEqualByComparingTo(new BigDecimal("10000.00"));
        assertThat(reconciliationService.trialBalance().isBalanced()).isTrue();

        alwaysRecovers.rollDailyTransitions(LocalDate.of(2026, 6, 4), false);
        LoanAccount recovered = loanService.findById(loan.id());
        assertThat(recovered.phase()).isEqualTo(LoanPhase.PERFORMING);
        assertThat(recovered.provisionAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(reconciliationService.trialBalance().isBalanced()).isTrue();

        List<LoanPhaseTransition> history = loanService.findDetailById(loan.id()).phaseHistory();
        assertThat(history).hasSize(4);
        assertThat(history).allSatisfy(h -> assertThat(h.journalEntryId()).isNotNull());
        assertThat(history.get(0).provisionDelta()).isEqualByComparingTo(new BigDecimal("10000.00"));
        assertThat(history.get(1).provisionDelta()).isEqualByComparingTo(new BigDecimal("40000.00"));
        assertThat(history.get(2).provisionDelta()).isEqualByComparingTo(new BigDecimal("-40000.00"));
        assertThat(history.get(3).provisionDelta()).isEqualByComparingTo(new BigDecimal("-10000.00"));
    }

    @Test
    void onlyBusinessLoansAreEverRolledMortgageAndConsumerStayPerformingRegardlessOfRolls() {
        Account account = openAccount();
        LoanAccount mortgage = loanService.originateAndDisburse(
                account.customerId(), account.id(), LoanType.MORTGAGE, new BigDecimal("100000.00"), 240);
        LoanAccount consumer = loanService.originateAndDisburse(
                account.customerId(), account.id(), LoanType.CONSUMER, new BigDecimal("5000.00"), 12);

        LoanPhaseTransitionService alwaysDowngrades =
                new LoanPhaseTransitionService(loanRepository, provisionPoster, events, transactionTemplate, alwaysRolls(0.0));
        alwaysDowngrades.rollDailyTransitions(LocalDate.of(2026, 6, 1), true);

        assertThat(loanService.findById(mortgage.id()).phase()).isEqualTo(LoanPhase.PERFORMING);
        assertThat(loanService.findById(consumer.id()).phase()).isEqualTo(LoanPhase.PERFORMING);
        assertThat(loanService.findById(mortgage.id()).provisionAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(loanService.findById(consumer.id()).provisionAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    /**
     * A repayment remeasures the provision against the reduced outstanding
     * principal, and a payoff releases it in full - never stranding it in
     * LOAN_LOSS_PROVISION. Aggregate singleton balances are safe to diff here:
     * nothing else posts to them between this test's own before/after reads.
     */
    @Test
    void repaymentRemeasuresTheProvisionAndPayoffReleasesItInFull() {
        LoanAccount loan = originateBusinessLoan(new BigDecimal("100000.00"));
        transactionService.deposit(loan.disbursementAccountId(), new BigDecimal("5000.00"));

        LoanPhaseTransitionService alwaysDowngrades =
                new LoanPhaseTransitionService(loanRepository, provisionPoster, events, transactionTemplate, alwaysRolls(0.0));
        alwaysDowngrades.rollDailyTransitions(LocalDate.of(2026, 7, 1), true);
        alwaysDowngrades.rollDailyTransitions(LocalDate.of(2026, 7, 2), true);
        assertThat(loanService.findById(loan.id()).provisionAmount()).isEqualByComparingTo(new BigDecimal("50000.00"));

        loanService.repay(loan.id(), loan.installmentAmount());
        LoanAccount afterInstallment = loanService.findById(loan.id());
        assertThat(afterInstallment.outstandingPrincipal()).isLessThan(new BigDecimal("100000.00"));
        assertThat(afterInstallment.provisionAmount()).isEqualByComparingTo(
                LoanPhaseTransitionService.provisionAmount(LoanPhase.NON_PERFORMING, afterInstallment.outstandingPrincipal()));
        assertThat(reconciliationService.trialBalance().isBalanced()).isTrue();

        BigDecimal provisionBefore = ledgerAccountService.singletonCreditBalance(LedgerAccountType.LOAN_LOSS_PROVISION);
        loanService.repay(loan.id(), new BigDecimal("105000.00"));
        LoanAccount paidOff = loanService.findById(loan.id());
        BigDecimal provisionAfter = ledgerAccountService.singletonCreditBalance(LedgerAccountType.LOAN_LOSS_PROVISION);

        assertThat(paidOff.status()).isEqualTo(LoanStatus.PAID_OFF);
        assertThat(paidOff.provisionAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(provisionBefore.subtract(provisionAfter)).isEqualByComparingTo(afterInstallment.provisionAmount());
        assertThat(reconciliationService.trialBalance().isBalanced()).isTrue();
    }
}
