package io.github.ivarm1984.banksim.loan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.github.ivarm1984.banksim.PostgresIntegrationTest;
import io.github.ivarm1984.banksim.account.Account;
import io.github.ivarm1984.banksim.account.AccountService;
import io.github.ivarm1984.banksim.account.AccountType;
import io.github.ivarm1984.banksim.centralbank.CentralBankService;
import io.github.ivarm1984.banksim.clock.ClockService;
import io.github.ivarm1984.banksim.customer.Customer;
import io.github.ivarm1984.banksim.customer.CustomerService;
import io.github.ivarm1984.banksim.ledger.LedgerReconciliationService;
import io.github.ivarm1984.banksim.transaction.TransactionService;

class LoanServiceTest extends PostgresIntegrationTest {

    private static final BigDecimal TWELVE = new BigDecimal("12");

    @Autowired
    private LoanService loanService;
    @Autowired
    private AccountService accountService;
    @Autowired
    private CustomerService customerService;
    @Autowired
    private ClockService clockService;
    @Autowired
    private CentralBankService centralBankService;
    @Autowired
    private LedgerReconciliationService reconciliationService;
    @Autowired
    private TransactionService transactionService;

    private Account openAccount() {
        Customer customer = customerService.create("Grace Hopper");
        return accountService.open(customer.id(), AccountType.CHECKING);
    }

    /** Repaying a loan requires interest on top of principal - top up the disbursement account so repayments don't fail on insufficient funds. */
    private void topUpForRepayment(long accountId) {
        transactionService.deposit(accountId, new BigDecimal("10000.00"));
    }

    /** Resets the clock to its deterministic epoch date, so the central bank policy rate (and thus loan pricing) is fixed. */
    private BigDecimal annualRateAtEpoch() {
        clockService.reset();
        return centralBankService.currentRates().policyRate().add(LoanService.RISK_SPREAD);
    }

    private static BigDecimal monthlyRateOf(BigDecimal annualRate) {
        return annualRate.divide(TWELVE, MathContext.DECIMAL64);
    }

    private static BigDecimal expectedInstallmentAmount(BigDecimal principal, BigDecimal annualRate, int termMonths) {
        BigDecimal monthlyRate = monthlyRateOf(annualRate);
        BigDecimal onePlusR = BigDecimal.ONE.add(monthlyRate);
        BigDecimal factor = onePlusR.pow(termMonths, MathContext.DECIMAL64);
        BigDecimal numerator = principal.multiply(monthlyRate).multiply(factor);
        BigDecimal denominator = factor.subtract(BigDecimal.ONE);
        return numerator.divide(denominator, 2, RoundingMode.HALF_UP);
    }

    @Test
    void originationComputesFixedRateAndAFullyAmortizingSchedule() {
        BigDecimal annualRate = annualRateAtEpoch();
        Account account = openAccount();
        BigDecimal principal = new BigDecimal("12000.00");

        LoanAccount loan = loanService.originateAndDisburse(account.customerId(), account.id(), principal, 12);

        assertThat(loan.annualRate()).isEqualByComparingTo(annualRate);
        assertThat(loan.installmentAmount()).isEqualByComparingTo(expectedInstallmentAmount(principal, annualRate, 12));
        assertThat(loan.outstandingPrincipal()).isEqualByComparingTo(principal);
        assertThat(loan.status()).isEqualTo(LoanStatus.ACTIVE);

        List<LoanInstallment> schedule = loanService.findDetailById(loan.id()).installments();
        assertThat(schedule).hasSize(12);

        BigDecimal expectedFirstInterest = principal.multiply(monthlyRateOf(annualRate)).setScale(2, RoundingMode.HALF_UP);
        assertThat(schedule.get(0).openingBalance()).isEqualByComparingTo(principal);
        assertThat(schedule.get(0).interestPortion()).isEqualByComparingTo(expectedFirstInterest);

        // The schedule must fully amortize: last installment zeroes the balance...
        assertThat(schedule.get(11).closingBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        // ...and every installment's principal portion sums back to the original principal.
        BigDecimal totalPrincipalScheduled = schedule.stream()
                .map(LoanInstallment::principalPortion)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(totalPrincipalScheduled).isEqualByComparingTo(principal);

        for (int i = 0; i < schedule.size(); i++) {
            LoanInstallment installment = schedule.get(i);
            assertThat(installment.closingBalance())
                    .isEqualByComparingTo(installment.openingBalance().subtract(installment.principalPortion()));
            if (i > 0) {
                assertThat(installment.openingBalance()).isEqualByComparingTo(schedule.get(i - 1).closingBalance());
            }
        }
    }

    @Test
    void disbursementPostsABalancedJournalEntryAndKeepsTrialBalanceZero() {
        annualRateAtEpoch();
        Account account = openAccount();
        BigDecimal balanceBefore = accountService.balanceOf(account.id());

        loanService.originateAndDisburse(account.customerId(), account.id(), new BigDecimal("5000.00"), 6);

        assertThat(accountService.balanceOf(account.id())).isEqualByComparingTo(balanceBefore.add(new BigDecimal("5000.00")));
        assertThat(reconciliationService.trialBalance().isBalanced()).isTrue();
    }

    @Test
    void normalRepaymentSplitsPrincipalAndInterestAndAdvancesInstallmentNumber() {
        annualRateAtEpoch();
        Account account = openAccount();
        LoanAccount loan = loanService.originateAndDisburse(account.customerId(), account.id(), new BigDecimal("12000.00"), 12);
        BigDecimal expectedInterest = loan.principal().multiply(monthlyRateOf(loan.annualRate())).setScale(2, RoundingMode.HALF_UP);

        LoanPayment payment = loanService.repay(loan.id(), loan.installmentAmount());

        assertThat(payment.paymentType()).isEqualTo(LoanPaymentType.NORMAL);
        assertThat(payment.interestPortion()).isEqualByComparingTo(expectedInterest);
        assertThat(payment.principalPortion()).isEqualByComparingTo(loan.installmentAmount().subtract(expectedInterest));
        assertThat(payment.journalEntryId()).isNotNull();

        LoanAccount reloaded = loanService.findById(loan.id());
        assertThat(reloaded.nextInstallmentNumber()).isEqualTo(2);
        assertThat(reloaded.outstandingPrincipal()).isEqualByComparingTo(loan.principal().subtract(payment.principalPortion()));
        assertThat(reconciliationService.trialBalance().isBalanced()).isTrue();
    }

    @Test
    void partialEarlyPayoffThenFullPayoffClosesTheLoanBeforeFullTerm() {
        annualRateAtEpoch();
        Account account = openAccount();
        LoanAccount loan = loanService.originateAndDisburse(account.customerId(), account.id(), new BigDecimal("12000.00"), 12);
        topUpForRepayment(account.id());
        LoanInstallment plannedFirst = loanService.findDetailById(loan.id()).installments().get(0);

        BigDecimal extraPayment = loan.installmentAmount().add(new BigDecimal("2000.00"));
        LoanPayment firstPayment = loanService.repay(loan.id(), extraPayment);

        assertThat(firstPayment.paymentType()).isEqualTo(LoanPaymentType.EARLY_PARTIAL);
        LoanAccount afterFirst = loanService.findById(loan.id());
        assertThat(afterFirst.nextInstallmentNumber()).isEqualTo(2);
        // Paid down further than the original plan's first installment would have.
        assertThat(afterFirst.outstandingPrincipal()).isLessThan(plannedFirst.closingBalance());

        BigDecimal secondInterestDue = afterFirst.outstandingPrincipal()
                .multiply(monthlyRateOf(afterFirst.annualRate()))
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal payoffAmount = afterFirst.outstandingPrincipal().add(secondInterestDue);

        LoanPayment secondPayment = loanService.repay(loan.id(), payoffAmount.add(new BigDecimal("5000.00")));

        assertThat(secondPayment.paymentType()).isEqualTo(LoanPaymentType.EARLY_PAYOFF);
        assertThat(secondPayment.amount()).isEqualByComparingTo(payoffAmount);
        LoanAccount finalState = loanService.findById(loan.id());
        assertThat(finalState.status()).isEqualTo(LoanStatus.PAID_OFF);
        assertThat(finalState.outstandingPrincipal()).isEqualByComparingTo(BigDecimal.ZERO);
        // Paid off in 2 installments' worth of payments, well short of the 12-month term.
        assertThat(reconciliationService.trialBalance().isBalanced()).isTrue();
    }

    @Test
    void fullEarlyPayoffClosesTheLoanAndFurtherRepaymentIsRejected() {
        annualRateAtEpoch();
        Account account = openAccount();
        LoanAccount loan = loanService.originateAndDisburse(account.customerId(), account.id(), new BigDecimal("12000.00"), 12);
        topUpForRepayment(account.id());
        BigDecimal interestDue = loan.principal().multiply(monthlyRateOf(loan.annualRate())).setScale(2, RoundingMode.HALF_UP);
        BigDecimal payoffAmount = loan.principal().add(interestDue);

        LoanPayment payment = loanService.repay(loan.id(), payoffAmount.add(new BigDecimal("999.00")));

        assertThat(payment.paymentType()).isEqualTo(LoanPaymentType.EARLY_PAYOFF);
        assertThat(payment.amount()).isEqualByComparingTo(payoffAmount);
        assertThat(payment.principalPortion()).isEqualByComparingTo(loan.principal());
        assertThat(payment.outstandingPrincipalAfter()).isEqualByComparingTo(BigDecimal.ZERO);

        LoanAccount finalState = loanService.findById(loan.id());
        assertThat(finalState.status()).isEqualTo(LoanStatus.PAID_OFF);

        assertThatThrownBy(() -> loanService.repay(loan.id(), new BigDecimal("1.00")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(reconciliationService.trialBalance().isBalanced()).isTrue();
    }

    @Test
    void trialBalanceStaysZeroAcrossAFullOriginationDisbursementRepaymentsAndPayoffSequence() {
        annualRateAtEpoch();
        Account account = openAccount();
        LoanAccount loan = loanService.originateAndDisburse(account.customerId(), account.id(), new BigDecimal("6000.00"), 6);
        topUpForRepayment(account.id());
        assertThat(reconciliationService.trialBalance().isBalanced()).isTrue();

        LoanAccount current = loan;
        for (int i = 0; i < 5; i++) {
            loanService.repay(loan.id(), current.installmentAmount());
            current = loanService.findById(loan.id());
            assertThat(reconciliationService.trialBalance().isBalanced()).isTrue();
        }

        BigDecimal finalInterestDue = current.outstandingPrincipal()
                .multiply(monthlyRateOf(current.annualRate()))
                .setScale(2, RoundingMode.HALF_UP);
        loanService.repay(loan.id(), current.outstandingPrincipal().add(finalInterestDue));

        LoanAccount finalState = loanService.findById(loan.id());
        assertThat(finalState.status()).isEqualTo(LoanStatus.PAID_OFF);
        assertThat(finalState.outstandingPrincipal()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(reconciliationService.trialBalance().isBalanced()).isTrue();
    }
}
