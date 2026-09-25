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
import io.github.ivarm1984.banksim.TestCustomers;
import io.github.ivarm1984.banksim.account.Account;
import io.github.ivarm1984.banksim.account.AccountService;
import io.github.ivarm1984.banksim.account.AccountType;
import io.github.ivarm1984.banksim.centralbank.CentralBankService;
import io.github.ivarm1984.banksim.clock.ClockService;
import io.github.ivarm1984.banksim.customer.CreditGrade;
import io.github.ivarm1984.banksim.customer.Customer;
import io.github.ivarm1984.banksim.customer.CustomerService;
import io.github.ivarm1984.banksim.customer.NewCustomer;
import io.github.ivarm1984.banksim.ledger.LedgerReconciliationService;
import io.github.ivarm1984.banksim.policy.PolicyLevers;
import io.github.ivarm1984.banksim.policy.PolicyLeversSnapshot;
import io.github.ivarm1984.banksim.treasury.TreasuryService;
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
    @Autowired
    private PolicyLevers policyLevers;

    private Account openAccount() {
        Customer customer = customerService.create(TestCustomers.affluent("Grace Hopper"));
        return accountService.open(customer.id(), AccountType.CHECKING);
    }

    /** Repaying a loan requires interest on top of principal - top up the disbursement account so repayments don't fail on insufficient funds. */
    private void topUpForRepayment(long accountId) {
        transactionService.deposit(accountId, new BigDecimal("10000.00"));
    }

    private Account openAccount(CreditGrade grade, String monthlyIncome) {
        Customer customer = customerService.create(new NewCustomer("Borrower " + grade, grade, new BigDecimal(monthlyIncome)));
        return accountService.open(customer.id(), AccountType.CHECKING);
    }

    /** Resets the clock to its deterministic epoch date, so the central bank policy rate (and thus loan pricing) is fixed. */
    private BigDecimal annualRateAtEpoch() {
        clockService.reset();
        return averageBorrowerRate(LoanType.CONSUMER);
    }

    /**
     * What a grade C, low-DSTI borrower pays at today's policy rate with the
     * default levers - hand-built from the pricing formula's parts: funding +
     * base margin + PD x LGD + risk weight x OCR x hurdle rate.
     */
    private BigDecimal averageBorrowerRate(LoanType type) {
        BigDecimal expectedLoss = CreditRisk.productDefaultProbability(type).multiply(CreditRisk.lossGivenDefault(type));
        BigDecimal capital = TreasuryService.performingRiskWeight(type)
                .multiply(TreasuryService.OVERALL_CAPITAL_REQUIREMENT)
                .multiply(RiskBasedPricing.CAPITAL_HURDLE_RATE);
        return centralBankService.currentRates().policyRate()
                .add(RiskBasedPricing.baseMargin(type)).add(expectedLoss).add(capital)
                .setScale(4, RoundingMode.HALF_UP);
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

        LoanAccount loan = loanService.originateAndDisburse(account.customerId(), account.id(), LoanType.CONSUMER, principal, 12);

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
    void mortgagePricesLowerThanConsumerForTheSamePrincipalAndTerm() {
        clockService.reset();
        Account account = openAccount();

        LoanAccount mortgage = loanService.originateAndDisburse(
                account.customerId(), account.id(), LoanType.MORTGAGE, new BigDecimal("100000.00"), 240);
        LoanAccount consumer = loanService.originateAndDisburse(
                account.customerId(), account.id(), LoanType.CONSUMER, new BigDecimal("5000.00"), 24);

        assertThat(mortgage.annualRate()).isLessThan(consumer.annualRate());
        assertThat(mortgage.annualRate()).isEqualByComparingTo(averageBorrowerRate(LoanType.MORTGAGE));
    }

    /**
     * PolicyLevers is a shared Spring singleton across the whole
     * Testcontainers-backed suite, so any test mutating it must restore the
     * original snapshot in a {@code finally} - same hygiene
     * TreasuryServiceTest's throttle test already applies.
     */
    @Test
    void loanSpreadLeversAdjustMortgageConsumerAndBusinessRatesIndependently() {
        clockService.reset();
        Account account = openAccount();
        PolicyLeversSnapshot original = policyLevers.state();
        try {
            policyLevers.update(new PolicyLeversSnapshot(
                    original.savingsRateSpread(), new BigDecimal("0.0050"), new BigDecimal("-0.0100"), new BigDecimal("0.0200"),
                    original.targetCapitalBuffer(), original.underwritingLooseness(), original.autoTapBorrowingFacility()));

            LoanAccount mortgage = loanService.originateAndDisburse(
                    account.customerId(), account.id(), LoanType.MORTGAGE, new BigDecimal("100000.00"), 240);
            LoanAccount consumer = loanService.originateAndDisburse(
                    account.customerId(), account.id(), LoanType.CONSUMER, new BigDecimal("5000.00"), 24);
            LoanAccount business = loanService.originateAndDisburse(
                    account.customerId(), account.id(), LoanType.BUSINESS, new BigDecimal("50000.00"), 60);

            assertThat(mortgage.annualRate())
                    .isEqualByComparingTo(averageBorrowerRate(LoanType.MORTGAGE).add(new BigDecimal("0.0050")));
            assertThat(consumer.annualRate())
                    .isEqualByComparingTo(averageBorrowerRate(LoanType.CONSUMER).add(new BigDecimal("-0.0100")));
            assertThat(business.annualRate())
                    .isEqualByComparingTo(averageBorrowerRate(LoanType.BUSINESS).add(new BigDecimal("0.0200")));
        } finally {
            policyLevers.update(original);
        }
    }

    @Test
    void worseGradesGetAHigherPdAndPayMoreForTheSameLoan() {
        clockService.reset();
        Account prime = openAccount(CreditGrade.A, "5000.00");
        Account subprime = openAccount(CreditGrade.E, "5000.00");

        LoanAccount good = loanService.originateAndDisburse(
                prime.customerId(), prime.id(), LoanType.CONSUMER, new BigDecimal("5000.00"), 24);
        LoanAccount bad = loanService.originateAndDisburse(
                subprime.customerId(), subprime.id(), LoanType.CONSUMER, new BigDecimal("5000.00"), 24);

        // 2.5% consumer baseline x 0.4 (A) / x 3.0 (E); a 5,000 loan is well under 30% DSTI.
        assertThat(good.probabilityOfDefault()).isEqualByComparingTo("0.010");
        assertThat(bad.probabilityOfDefault()).isEqualByComparingTo("0.075");
        // The only price difference is the expected loss: (7.5% - 1.0%) x 60% LGD = 3.90%.
        assertThat(bad.annualRate().subtract(good.annualRate())).isEqualByComparingTo("0.0390");
    }

    @Test
    void existingDebtRaisesTheDebtServiceToIncomeAndWithItThePd() {
        clockService.reset();
        Account account = openAccount(CreditGrade.C, "3000.00");

        LoanAccount first = loanService.originateAndDisburse(
                account.customerId(), account.id(), LoanType.CONSUMER, new BigDecimal("12000.00"), 12);
        LoanAccount second = loanService.originateAndDisburse(
                account.customerId(), account.id(), LoanType.CONSUMER, new BigDecimal("6000.00"), 12);

        LoanPricing firstPricing = loanService.findDetailById(first.id()).pricing();
        LoanPricing secondPricing = loanService.findDetailById(second.id()).pricing();
        // ~1,060/month on 3,000 is in the 30-40% band; another ~530 on top lands above 50%.
        assertThat(firstPricing.debtServiceToIncome()).isBetween(new BigDecimal("0.30"), new BigDecimal("0.40"));
        assertThat(secondPricing.debtServiceToIncome()).isBetween(new BigDecimal("0.50"), new BigDecimal("0.60"));
        assertThat(first.probabilityOfDefault()).isEqualByComparingTo("0.0375");
        assertThat(second.probabilityOfDefault()).isEqualByComparingTo("0.100");
        assertThat(second.annualRate()).isGreaterThan(first.annualRate());
    }

    @Test
    void aLoanThatWouldTakeDebtServiceAboveSixtyPercentOfIncomeIsDeclined() {
        clockService.reset();
        Account account = openAccount(CreditGrade.A, "2000.00");

        assertThatThrownBy(() -> loanService.originateAndDisburse(
                account.customerId(), account.id(), LoanType.BUSINESS, new BigDecimal("100000.00"), 60))
                .isInstanceOf(LoanDeclinedException.class)
                .hasMessageContaining("affordability limit");
        assertThat(loanService.findByAccountId(account.id())).isEmpty();
    }

    @Test
    void theUnderwritingLoosenessLeverSetsThePdApprovalCutoff() {
        clockService.reset();
        Account account = openAccount(CreditGrade.E, "5000.00");
        PolicyLeversSnapshot original = policyLevers.state();
        try {
            // Grade E consumer PD is 7.5%. Looseness 0.1 -> cutoff 2% + 28% x 0.1 = 4.8%: declined.
            policyLevers.update(withLooseness(original, new BigDecimal("0.10")));
            assertThatThrownBy(() -> loanService.originateAndDisburse(
                    account.customerId(), account.id(), LoanType.CONSUMER, new BigDecimal("5000.00"), 24))
                    .isInstanceOf(LoanDeclinedException.class)
                    .hasMessageContaining("approval cutoff");

            // Looseness 0.25 -> 9%: approved.
            policyLevers.update(withLooseness(original, new BigDecimal("0.25")));
            LoanAccount loan = loanService.originateAndDisburse(
                    account.customerId(), account.id(), LoanType.CONSUMER, new BigDecimal("5000.00"), 24);
            assertThat(loan.status()).isEqualTo(LoanStatus.ACTIVE);
        } finally {
            policyLevers.update(original);
        }
    }

    private static PolicyLeversSnapshot withLooseness(PolicyLeversSnapshot s, BigDecimal looseness) {
        return new PolicyLeversSnapshot(
                s.savingsRateSpread(), s.mortgageSpreadAdjustment(), s.consumerSpreadAdjustment(), s.businessSpreadAdjustment(),
                s.targetCapitalBuffer(), looseness, s.autoTapBorrowingFacility());
    }

    @Test
    void thePricingBreakdownAddsUpToTheLoanRateAndIsKeptWithTheLoan() {
        clockService.reset();
        Account account = openAccount(CreditGrade.D, "4000.00");

        LoanAccount loan = loanService.originateAndDisburse(
                account.customerId(), account.id(), LoanType.BUSINESS, new BigDecimal("30000.00"), 60);
        LoanPricing pricing = loanService.findDetailById(loan.id()).pricing();

        assertThat(pricing.creditGrade()).isEqualTo(CreditGrade.D);
        assertThat(pricing.monthlyIncome()).isEqualByComparingTo("4000.00");
        assertThat(pricing.pricingProbabilityOfDefault()).isEqualByComparingTo(loan.probabilityOfDefault());
        assertThat(pricing.annualRate()).isEqualByComparingTo(loan.annualRate());
        assertThat(pricing.policyRate().add(pricing.baseMargin()).add(pricing.expectedLossSpread())
                .add(pricing.capitalSpread()).add(pricing.spreadAdjustment()).setScale(4, RoundingMode.HALF_UP))
                .isEqualByComparingTo(loan.annualRate());
        // The day-one Stage 1 allowance uses the borrower's own PD, not the product baseline.
        assertThat(loanService.findById(loan.id()).provisionAmount()).isEqualByComparingTo(new BigDecimal("30000.00")
                .multiply(loan.probabilityOfDefault()).multiply(CreditRisk.lossGivenDefault(LoanType.BUSINESS))
                .setScale(2, RoundingMode.HALF_UP));
    }

    @Test
    void businessLoanPricesBetweenMortgageAndConsumerForTheSamePrincipalAndTerm() {
        clockService.reset();
        Account account = openAccount();

        LoanAccount mortgage = loanService.originateAndDisburse(
                account.customerId(), account.id(), LoanType.MORTGAGE, new BigDecimal("100000.00"), 60);
        LoanAccount business = loanService.originateAndDisburse(
                account.customerId(), account.id(), LoanType.BUSINESS, new BigDecimal("100000.00"), 60);
        LoanAccount consumer = loanService.originateAndDisburse(
                account.customerId(), account.id(), LoanType.CONSUMER, new BigDecimal("100000.00"), 60);

        assertThat(business.annualRate()).isGreaterThan(mortgage.annualRate());
        assertThat(business.annualRate()).isLessThan(consumer.annualRate());
        assertThat(business.phase()).isEqualTo(LoanPhase.PERFORMING);
        assertThat(business.provisionAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void secondMortgageOnTheSameAccountIsRejected() {
        clockService.reset();
        Account account = openAccount();
        loanService.originateAndDisburse(account.customerId(), account.id(), LoanType.MORTGAGE, new BigDecimal("100000.00"), 240);

        assertThatThrownBy(() -> loanService.originateAndDisburse(
                account.customerId(), account.id(), LoanType.MORTGAGE, new BigDecimal("50000.00"), 180))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void disbursementPostsABalancedJournalEntryAndKeepsTrialBalanceZero() {
        annualRateAtEpoch();
        Account account = openAccount();
        BigDecimal balanceBefore = accountService.balanceOf(account.id());

        loanService.originateAndDisburse(account.customerId(), account.id(), LoanType.CONSUMER, new BigDecimal("5000.00"), 6);

        assertThat(accountService.balanceOf(account.id())).isEqualByComparingTo(balanceBefore.add(new BigDecimal("5000.00")));
        assertThat(reconciliationService.trialBalance().isBalanced()).isTrue();
    }

    @Test
    void normalRepaymentSplitsPrincipalAndInterestAndAdvancesInstallmentNumber() {
        annualRateAtEpoch();
        Account account = openAccount();
        LoanAccount loan = loanService.originateAndDisburse(account.customerId(), account.id(), LoanType.CONSUMER, new BigDecimal("12000.00"), 12);
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
        LoanAccount loan = loanService.originateAndDisburse(account.customerId(), account.id(), LoanType.CONSUMER, new BigDecimal("12000.00"), 12);
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
        LoanAccount loan = loanService.originateAndDisburse(account.customerId(), account.id(), LoanType.CONSUMER, new BigDecimal("12000.00"), 12);
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
        LoanAccount loan = loanService.originateAndDisburse(account.customerId(), account.id(), LoanType.CONSUMER, new BigDecimal("6000.00"), 6);
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
