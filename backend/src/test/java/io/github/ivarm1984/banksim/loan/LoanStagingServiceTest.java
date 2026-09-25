package io.github.ivarm1984.banksim.loan;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.github.ivarm1984.banksim.PostgresIntegrationTest;
import io.github.ivarm1984.banksim.TestCustomers;
import io.github.ivarm1984.banksim.account.Account;
import io.github.ivarm1984.banksim.account.AccountService;
import io.github.ivarm1984.banksim.account.AccountType;
import io.github.ivarm1984.banksim.clock.ClockService;
import io.github.ivarm1984.banksim.customer.Customer;
import io.github.ivarm1984.banksim.customer.CustomerService;
import io.github.ivarm1984.banksim.eventinjector.RecessionShockService;
import io.github.ivarm1984.banksim.ledger.LedgerAccountService;
import io.github.ivarm1984.banksim.ledger.LedgerAccountType;
import io.github.ivarm1984.banksim.ledger.LedgerReconciliationService;
import io.github.ivarm1984.banksim.transaction.TransactionService;

/**
 * Days-past-due staging and IFRS 9 provisioning against real loans. The
 * daily pass ({@code evaluateDaily}) restages every active loan in the
 * shared Testcontainers DB, so these tests stage only their own loan via
 * the per-loan {@code evaluateLoan}, and assert against that loan's own row,
 * its phase history, and the suite-wide trial-balance invariant - never
 * aggregate singleton balances that other loans could also move, except
 * across a single call nothing else runs during.
 */
class LoanStagingServiceTest extends PostgresIntegrationTest {

    @Autowired
    private LoanService loanService;
    @Autowired
    private LoanStagingService stagingService;
    @Autowired
    private LedgerAccountService ledgerAccountService;
    @Autowired
    private TransactionService transactionService;
    @Autowired
    private AccountService accountService;
    @Autowired
    private CustomerService customerService;
    @Autowired
    private ClockService clockService;
    @Autowired
    private RecessionShockService recessionShockService;
    @Autowired
    private LedgerReconciliationService reconciliationService;

    private LoanAccount originateBusinessLoan(BigDecimal principal) {
        Customer customer = customerService.create(TestCustomers.affluent("Test Business Borrower"));
        Account account = accountService.open(customer.id(), AccountType.CHECKING);
        return loanService.originateAndDisburse(account.customerId(), account.id(), LoanType.BUSINESS, principal, 60);
    }

    private static LocalDate firstDueDate(LoanAccount loan) {
        return loan.originationDate().plusMonths(1);
    }

    private boolean recessionToday() {
        return recessionShockService.isActive(clockService.state().simulatedTime().toLocalDate());
    }

    @Test
    void disbursementBooksTheDayOneStage1TwelveMonthEcl() {
        LoanAccount loan = originateBusinessLoan(new BigDecimal("100000.00"));

        BigDecimal expected = CreditRisk.expectedCreditLoss(
                LoanType.BUSINESS, CreditRisk.productDefaultProbability(LoanType.BUSINESS), LoanPhase.PERFORMING, new BigDecimal("100000.00"), 60, recessionToday());
        assertThat(expected).isPositive();
        assertThat(loanService.findById(loan.id()).provisionAmount()).isEqualByComparingTo(expected);
        assertThat(reconciliationService.trialBalance().isBalanced()).isTrue();
    }

    @Test
    void arrearsMoveTheLoanToStage2At30DaysAndIntoDefaultAt90WithMatchingProvisions() {
        LoanAccount loan = originateBusinessLoan(new BigDecimal("100000.00"));
        LocalDate due = firstDueDate(loan);

        stagingService.evaluateLoan(loan.id(), due.plusDays(29), false);
        assertThat(loanService.findById(loan.id()).phase()).isEqualTo(LoanPhase.PERFORMING);

        stagingService.evaluateLoan(loan.id(), due.plusDays(30), false);
        LoanAccount stage2 = loanService.findById(loan.id());
        assertThat(stage2.phase()).isEqualTo(LoanPhase.UNDERPERFORMING);
        assertThat(stage2.provisionAmount()).isEqualByComparingTo(CreditRisk.expectedCreditLoss(
                LoanType.BUSINESS, CreditRisk.productDefaultProbability(LoanType.BUSINESS), LoanPhase.UNDERPERFORMING, stage2.outstandingPrincipal(), 60, false));
        assertThat(stage2.provisionAmount()).isGreaterThan(CreditRisk.expectedCreditLoss(
                LoanType.BUSINESS, CreditRisk.productDefaultProbability(LoanType.BUSINESS), LoanPhase.PERFORMING, stage2.outstandingPrincipal(), 60, false));

        stagingService.evaluateLoan(loan.id(), due.plusDays(90), false);
        LoanAccount defaulted = loanService.findById(loan.id());
        assertThat(defaulted.phase()).isEqualTo(LoanPhase.NON_PERFORMING);
        // Stage 3: PD = 1, so the allowance is LGD x EAD.
        assertThat(defaulted.provisionAmount()).isEqualByComparingTo(new BigDecimal("45000.00"));
        assertThat(reconciliationService.trialBalance().isBalanced()).isTrue();

        List<LoanPhaseTransition> history = loanService.findDetailById(loan.id()).phaseHistory();
        assertThat(history).extracting(LoanPhaseTransition::toPhase)
                .containsExactly(LoanPhase.UNDERPERFORMING, LoanPhase.NON_PERFORMING);
        assertThat(history).allSatisfy(h -> assertThat(h.journalEntryId()).isNotNull());
    }

    /**
     * Catching up on arrears doesn't cure a default: the loan stays
     * NON_PERFORMING through a 3-month probation of being fully current, and
     * any new arrears during it restart the clock.
     */
    @Test
    void aDefaultedLoanOnlyCuresAfterAThreeMonthProbation() {
        LoanAccount loan = originateBusinessLoan(new BigDecimal("100000.00"));
        LocalDate due = firstDueDate(loan);
        stagingService.evaluateLoan(loan.id(), due.plusDays(95), false);
        assertThat(loanService.findById(loan.id()).phase()).isEqualTo(LoanPhase.NON_PERFORMING);

        // Pay the ~4 overdue installments plus 6 ahead, so the loan stays current through probation.
        for (int i = 0; i < 10; i++) {
            loanService.repay(loan.id(), loan.installmentAmount());
        }
        LocalDate caughtUp = due.plusDays(96);
        stagingService.evaluateLoan(loan.id(), caughtUp, false);
        LoanAccount onProbation = loanService.findById(loan.id());
        assertThat(onProbation.phase()).isEqualTo(LoanPhase.NON_PERFORMING);
        assertThat(onProbation.probationStartDate()).isEqualTo(caughtUp);

        stagingService.evaluateLoan(loan.id(), caughtUp.plusMonths(3).minusDays(1), false);
        assertThat(loanService.findById(loan.id()).phase()).isEqualTo(LoanPhase.NON_PERFORMING);

        stagingService.evaluateLoan(loan.id(), caughtUp.plusMonths(3), false);
        LoanAccount cured = loanService.findById(loan.id());
        assertThat(cured.phase()).isEqualTo(LoanPhase.PERFORMING);
        assertThat(cured.probationStartDate()).isNull();
        assertThat(cured.provisionAmount()).isEqualByComparingTo(CreditRisk.expectedCreditLoss(
                LoanType.BUSINESS, CreditRisk.productDefaultProbability(LoanType.BUSINESS), LoanPhase.PERFORMING, cured.outstandingPrincipal(), CreditRisk.remainingMonths(cured), false));
        assertThat(reconciliationService.trialBalance().isBalanced()).isTrue();
    }

    /**
     * A Stage 3 loan's interest is recognised on its net carrying amount; the
     * repayment remeasures the allowance on the reduced exposure, and payoff
     * releases what's left in full. The INTEREST_INCOME/LOAN_LOSS_PROVISION
     * diffs are safe here: nothing else posts between each before/after read.
     */
    @Test
    void stage3InterestIsRecognisedNetAndPayoffReleasesTheAllowanceInFull() {
        LoanAccount loan = originateBusinessLoan(new BigDecimal("100000.00"));
        transactionService.deposit(loan.disbursementAccountId(), new BigDecimal("10000.00"));
        stagingService.evaluateLoan(loan.id(), firstDueDate(loan).plusDays(90), false);
        LoanAccount defaulted = loanService.findById(loan.id());

        BigDecimal incomeBefore = ledgerAccountService.singletonCreditBalance(LedgerAccountType.INTEREST_INCOME);
        LoanPayment payment = loanService.repay(loan.id(), loan.installmentAmount());
        BigDecimal incomeRecognised = ledgerAccountService.singletonCreditBalance(LedgerAccountType.INTEREST_INCOME).subtract(incomeBefore);

        BigDecimal netShare = BigDecimal.ONE.subtract(defaulted.provisionAmount().divide(defaulted.outstandingPrincipal(), 10, RoundingMode.HALF_UP));
        assertThat(incomeRecognised).isEqualByComparingTo(payment.interestPortion().multiply(netShare).setScale(2, RoundingMode.HALF_UP));
        assertThat(incomeRecognised).isLessThan(payment.interestPortion());

        LoanAccount afterInstallment = loanService.findById(loan.id());
        assertThat(afterInstallment.provisionAmount()).isEqualByComparingTo(
                afterInstallment.outstandingPrincipal().multiply(new BigDecimal("0.45")).setScale(2, RoundingMode.HALF_UP));

        BigDecimal provisionBefore = ledgerAccountService.singletonCreditBalance(LedgerAccountType.LOAN_LOSS_PROVISION);
        loanService.repay(loan.id(), new BigDecimal("105000.00"));
        LoanAccount paidOff = loanService.findById(loan.id());
        BigDecimal provisionAfter = ledgerAccountService.singletonCreditBalance(LedgerAccountType.LOAN_LOSS_PROVISION);

        assertThat(paidOff.status()).isEqualTo(LoanStatus.PAID_OFF);
        assertThat(paidOff.provisionAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(provisionBefore.subtract(provisionAfter)).isEqualByComparingTo(afterInstallment.provisionAmount());
        assertThat(reconciliationService.trialBalance().isBalanced()).isTrue();
    }

    /**
     * IFRS 9 is forward-looking: a recession raises every performing loan's
     * PD, so the whole book's allowance goes up before a single payment is
     * missed - and comes back down once it ends.
     */
    @Test
    void aRecessionRemeasuresTheWholeBookUpAndItsEndBringsItBackDown() {
        LoanAccount loan = originateBusinessLoan(new BigDecimal("100000.00"));
        LocalDate date = loan.originationDate();
        try {
            stagingService.remeasureAll(date, true);
            assertThat(loanService.findById(loan.id()).provisionAmount()).isEqualByComparingTo(CreditRisk.expectedCreditLoss(
                    LoanType.BUSINESS, CreditRisk.productDefaultProbability(LoanType.BUSINESS), LoanPhase.PERFORMING, new BigDecimal("100000.00"), 60, true));
            assertThat(reconciliationService.trialBalance().isBalanced()).isTrue();
        } finally {
            stagingService.remeasureAll(date, false);
        }
        assertThat(loanService.findById(loan.id()).provisionAmount()).isEqualByComparingTo(CreditRisk.expectedCreditLoss(
                LoanType.BUSINESS, CreditRisk.productDefaultProbability(LoanType.BUSINESS), LoanPhase.PERFORMING, new BigDecimal("100000.00"), 60, false));
        assertThat(reconciliationService.trialBalance().isBalanced()).isTrue();
    }
}
