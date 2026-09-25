package io.github.ivarm1984.banksim.treasury;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
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
import io.github.ivarm1984.banksim.ledger.EntryType;
import io.github.ivarm1984.banksim.ledger.JournalEntryRequest;
import io.github.ivarm1984.banksim.ledger.LedgerAccountService;
import io.github.ivarm1984.banksim.ledger.LedgerAccountType;
import io.github.ivarm1984.banksim.ledger.LedgerLineRequest;
import io.github.ivarm1984.banksim.ledger.LedgerReconciliationService;
import io.github.ivarm1984.banksim.ledger.LedgerService;
import io.github.ivarm1984.banksim.loan.CreditExposure;
import io.github.ivarm1984.banksim.loan.LoanExposureService;
import io.github.ivarm1984.banksim.loan.LoanPhase;
import io.github.ivarm1984.banksim.loan.LoanService;
import io.github.ivarm1984.banksim.loan.LoanType;
import io.github.ivarm1984.banksim.policy.PolicyLevers;
import io.github.ivarm1984.banksim.policy.PolicyLeversSnapshot;
import io.github.ivarm1984.banksim.transaction.TransactionService;

class TreasuryServiceTest extends PostgresIntegrationTest {

    private static final int SCALE = 6;

    @Autowired
    private TreasuryService treasuryService;
    @Autowired
    private TreasuryRatioRepository treasuryRatioRepository;
    @Autowired
    private AccountService accountService;
    @Autowired
    private CustomerService customerService;
    @Autowired
    private TransactionService transactionService;
    @Autowired
    private LoanService loanService;
    @Autowired
    private LedgerAccountService ledgerAccountService;
    @Autowired
    private LedgerReconciliationService reconciliationService;
    @Autowired
    private ClockService clockService;
    @Autowired
    private CentralBankService centralBankService;
    @Autowired
    private PolicyLevers policyLevers;
    @Autowired
    private LedgerService ledgerService;
    @Autowired
    private LoanExposureService loanExposureService;

    private Account openAccount() {
        Customer customer = customerService.create("Grace Hopper");
        return accountService.open(customer.id(), AccountType.CHECKING);
    }

    /**
     * Every other test class shares this suite's single Postgres container, so
     * global ledger totals aren't zero-based here - this asserts the persisted
     * ratios are internally consistent with the snapshot's own raw balances
     * (a hand-computable formula), not against any absolute expected totals.
     * Originates a loan first so loans-receivable is guaranteed positive
     * regardless of what other test classes have or haven't run yet, keeping
     * NSFR/CAR's denominators non-zero.
     */
    @Test
    void persistedRatiosMatchTheSnapshotsOwnRawBalances() {
        Account account = openAccount();
        transactionService.deposit(account.id(), new BigDecimal("5000.00"));
        loanService.originateAndDisburse(account.customerId(), account.id(), LoanType.CONSUMER, new BigDecimal("1000.00"), 12);

        TreasuryRatioSnapshot snapshot = treasuryService.computeAndPersist(LocalDate.of(2030, 1, 1));

        BigDecimal requiredReserves = snapshot.customerDeposits().multiply(TreasuryService.MIN_RESERVE_REQUIREMENT_RATE);
        BigDecimal estimatedOutflow = snapshot.customerDeposits().multiply(TreasuryService.LCR_OUTFLOW_RATE);
        BigDecimal hqla = snapshot.bankCash().add(snapshot.centralBankReserves());
        BigDecimal availableStableFunding =
                snapshot.capitalBase().add(snapshot.customerDeposits().multiply(TreasuryService.NSFR_DEPOSIT_ASF_FACTOR));
        BigDecimal requiredStableFunding = snapshot.netLoans().multiply(TreasuryService.NSFR_LOAN_RSF_FACTOR);
        BigDecimal riskWeightedAssets = snapshot.riskWeightedAssets();

        assertThat(snapshot.requiredReserves()).isEqualByComparingTo(requiredReserves);
        assertThat(snapshot.loanToDepositRatio())
                .isEqualByComparingTo(snapshot.loansReceivable().divide(snapshot.customerDeposits(), SCALE, RoundingMode.HALF_UP));
        assertThat(snapshot.liquidityCoverageRatio())
                .isEqualByComparingTo(hqla.divide(estimatedOutflow, SCALE, RoundingMode.HALF_UP));
        assertThat(snapshot.netStableFundingRatio())
                .isEqualByComparingTo(availableStableFunding.divide(requiredStableFunding, SCALE, RoundingMode.HALF_UP));
        assertThat(snapshot.reserveCoverageRatio())
                .isEqualByComparingTo(snapshot.centralBankReserves().divide(requiredReserves, SCALE, RoundingMode.HALF_UP));
        assertThat(snapshot.capitalAdequacyRatio())
                .isEqualByComparingTo(snapshot.capitalBase().divide(riskWeightedAssets, SCALE, RoundingMode.HALF_UP));
    }

    @Test
    void originatingAndDisbursingALoanMovesLoansReceivableAndDepositsByExactlyThePrincipalAndLeavesReservesUntouched() {
        TreasuryRatioSnapshot before = treasuryService.computeAndPersist(LocalDate.of(2030, 2, 1));

        Account account = openAccount();
        transactionService.deposit(account.id(), new BigDecimal("500.00"));
        BigDecimal principal = new BigDecimal("10000.00");
        loanService.originateAndDisburse(account.customerId(), account.id(), LoanType.CONSUMER, principal, 12);

        TreasuryRatioSnapshot after = treasuryService.computeAndPersist(LocalDate.of(2030, 2, 2));

        // Disbursement is Debit LOAN_RECEIVABLE / Credit the customer's checking
        // account, so loans-receivable and deposits both move by exactly the
        // principal. The deposit made just before disbursement is Debit
        // BANK_CASH / Credit the customer's checking account, so it moves
        // bank-cash and deposits by 500 but leaves central-bank reserves untouched.
        assertThat(after.loansReceivable().subtract(before.loansReceivable())).isEqualByComparingTo(principal);
        assertThat(after.customerDeposits().subtract(before.customerDeposits()))
                .isEqualByComparingTo(principal.add(new BigDecimal("500.00")));
        assertThat(after.bankCash().subtract(before.bankCash())).isEqualByComparingTo(new BigDecimal("500.00"));
        assertThat(after.centralBankReserves()).isEqualByComparingTo(before.centralBankReserves());
    }

    /**
     * {@code applyFeedback} only reads the raw balance fields off the
     * snapshot it's handed - it never re-reads the live ledger to decide
     * whether there's a breach - so a hand-crafted snapshot can force an
     * exact, reproducible breach regardless of whatever the rest of this
     * shared-container suite has posted. Only the resulting ledger
     * entries (central bank reserves/borrowings) are real and shared, so
     * every assertion here is a before/after delta, same as the rest of
     * this file.
     */
    @Test
    void liquidityBreachTriggersAnAutoBorrowAndRecoveryRepaysItBack() {
        TreasuryRatioSnapshot reservesBefore = treasuryService.computeAndPersist(LocalDate.of(2030, 3, 1));

        // 100,000 deposits need 10,000 of HQLA (10% outflow assumption) but
        // only 1,000 of cash/reserves are on hand - hqla headroom is -9,000.
        TreasuryRatioSnapshot breach = new TreasuryRatioSnapshot(
                null, LocalDate.of(2091, 1, 1), BigDecimal.ZERO, new BigDecimal("1000.00"), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("100000.00"), BigDecimal.ZERO, null, null, null, new BigDecimal("1000.00"), null, null, null, null, null, null);
        treasuryService.applyFeedback(breach);

        BigDecimal outstandingAfterBorrow = ledgerAccountService.singletonCreditBalance(LedgerAccountType.CENTRAL_BANK_BORROWINGS);
        TreasuryRatioSnapshot reservesAfterBorrow = treasuryService.computeAndPersist(LocalDate.of(2030, 3, 2));
        assertThat(reservesAfterBorrow.centralBankReserves().subtract(reservesBefore.centralBankReserves()))
                .isEqualByComparingTo(new BigDecimal("9000.00"));
        assertThat(reconciliationService.trialBalance().isBalanced()).isTrue();

        // Recovery: headroom deliberately huge - enough to fully clear this
        // test's own draw *and* any leftover balance other tests in this
        // shared-container suite may have left outstanding, regardless of
        // run order, so the assertions below don't depend on starting from
        // an assumed-zero facility balance.
        TreasuryRatioSnapshot healthy = new TreasuryRatioSnapshot(
                null, LocalDate.of(2091, 1, 2), BigDecimal.ZERO, new BigDecimal("10000000.00"), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("1000.00"), BigDecimal.ZERO, null, null, null, new BigDecimal("100.00"), null, null, null, null, null, null);
        treasuryService.applyFeedback(healthy);

        BigDecimal borrowingsAfterRepay = ledgerAccountService.singletonCreditBalance(LedgerAccountType.CENTRAL_BANK_BORROWINGS);
        TreasuryRatioSnapshot reservesAfterRepay = treasuryService.computeAndPersist(LocalDate.of(2030, 3, 3));
        assertThat(borrowingsAfterRepay).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(reservesAfterBorrow.centralBankReserves().subtract(reservesAfterRepay.centralBankReserves()))
                .isEqualByComparingTo(outstandingAfterBorrow);
        assertThat(reconciliationService.trialBalance().isBalanced()).isTrue();
    }

    /**
     * Cleans up in a {@code finally} - the persisted snapshot this test
     * forces is the "latest" row every other test class's
     * {@code isLoanOriginationThrottled()} check would see for the rest of
     * this shared-container suite, so leaving it in place would break every
     * later test that originates a loan.
     */
    @Test
    void capitalBreachThrottlesLoanOriginationUntilRatiosRecover() {
        try {
            treasuryRatioRepository.insert(
                    LocalDate.of(2092, 1, 1), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, null, null, null, null, new BigDecimal("0.500000"), BigDecimal.ZERO, null, new BigDecimal("0.010000"));

            assertThat(treasuryService.isLoanOriginationThrottled()).isTrue();
            Account account = openAccount();
            assertThatThrownBy(() -> loanService.originateAndDisburse(account.customerId(), account.id(), LoanType.CONSUMER, new BigDecimal("1000.00"), 6))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            treasuryRatioRepository.insert(
                    LocalDate.of(2092, 1, 2), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, null, null, null, null, new BigDecimal("10.000000"), BigDecimal.ZERO, null, new BigDecimal("1.000000"));
        }
        assertThat(treasuryService.isLoanOriginationThrottled()).isFalse();
    }

    @Test
    void borrowingInterestAccruesDailyAtTheMarginalLendingRate() {
        TreasuryRatioSnapshot breach = new TreasuryRatioSnapshot(
                null, LocalDate.of(2093, 1, 1), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("500000.00"), BigDecimal.ZERO, null, null, null, BigDecimal.ZERO, null, null, null, null, null, null);
        treasuryService.applyFeedback(breach);
        BigDecimal outstandingBefore = ledgerAccountService.singletonCreditBalance(LedgerAccountType.CENTRAL_BANK_BORROWINGS);

        clockService.reset();
        BigDecimal marginalLendingRate = centralBankService.currentRates().marginalLendingRate();
        LocalDate date = clockService.state().simulatedTime().toLocalDate();

        treasuryService.accrueBorrowingInterest(date);

        BigDecimal outstandingAfter = ledgerAccountService.singletonCreditBalance(LedgerAccountType.CENTRAL_BANK_BORROWINGS);
        BigDecimal expectedInterest = outstandingBefore.multiply(marginalLendingRate)
                .divide(new BigDecimal("365"), 10, RoundingMode.HALF_UP)
                .setScale(2, RoundingMode.HALF_UP);
        assertThat(outstandingAfter.subtract(outstandingBefore)).isEqualByComparingTo(expectedInterest);
        assertThat(reconciliationService.trialBalance().isBalanced()).isTrue();
    }

    /**
     * {@code targetCapitalBuffer} is a management buffer on top of OCR
     * (12.5%) - a snapshot at 13% CAR is fine with no buffer but throttles
     * once a 2% buffer puts the target at 14.5%. NSFR's 100% isn't shifted.
     * Cleans up in a {@code finally}/afterward, same reasoning as
     * {@link #capitalBreachThrottlesLoanOriginationUntilRatiosRecover}.
     */
    @Test
    void capitalBufferLeverRaisesTheThrottleThreshold() {
        PolicyLeversSnapshot original = policyLevers.state();
        try {
            treasuryRatioRepository.insert(
                    LocalDate.of(2094, 1, 1), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, null, null, null, null, new BigDecimal("1.010000"), BigDecimal.ZERO, null, new BigDecimal("0.130000"));
            assertThat(treasuryService.isLoanOriginationThrottled()).isFalse();

            policyLevers.update(new PolicyLeversSnapshot(
                    original.savingsRateSpread(), original.mortgageSpreadAdjustment(), original.consumerSpreadAdjustment(),
                    original.businessSpreadAdjustment(), new BigDecimal("0.02"), original.underwritingLooseness(),
                    original.autoTapBorrowingFacility()));

            assertThat(treasuryService.isLoanOriginationThrottled()).isTrue();
        } finally {
            policyLevers.update(original);
            treasuryRatioRepository.insert(
                    LocalDate.of(2094, 1, 2), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, null, null, null, null, new BigDecimal("10.000000"), BigDecimal.ZERO, null, new BigDecimal("1.000000"));
        }
    }

    @Test
    void autoTapLeverDisabledSkipsTheAutoBorrowOnALiquidityBreach() {
        PolicyLeversSnapshot original = policyLevers.state();
        try {
            policyLevers.update(new PolicyLeversSnapshot(
                    original.savingsRateSpread(), original.mortgageSpreadAdjustment(), original.consumerSpreadAdjustment(),
                    original.businessSpreadAdjustment(), original.targetCapitalBuffer(), original.underwritingLooseness(), false));

            BigDecimal outstandingBefore = ledgerAccountService.singletonCreditBalance(LedgerAccountType.CENTRAL_BANK_BORROWINGS);
            TreasuryRatioSnapshot reservesBefore = treasuryService.computeAndPersist(LocalDate.of(2095, 1, 1));

            // Same shortfall shape as liquidityBreachTriggersAnAutoBorrowAndRecoveryRepaysItBack.
            TreasuryRatioSnapshot breach = new TreasuryRatioSnapshot(
                    null, LocalDate.of(2095, 1, 2), BigDecimal.ZERO, new BigDecimal("1000.00"), BigDecimal.ZERO, BigDecimal.ZERO,
                    new BigDecimal("100000.00"), BigDecimal.ZERO, null, null, null, new BigDecimal("1000.00"), null, null, null, null, null, null);
            treasuryService.applyFeedback(breach);

            BigDecimal outstandingAfter = ledgerAccountService.singletonCreditBalance(LedgerAccountType.CENTRAL_BANK_BORROWINGS);
            TreasuryRatioSnapshot reservesAfter = treasuryService.computeAndPersist(LocalDate.of(2095, 1, 3));

            assertThat(outstandingAfter).isEqualByComparingTo(outstandingBefore);
            assertThat(reservesAfter.centralBankReserves()).isEqualByComparingTo(reservesBefore.centralBankReserves());
            assertThat(reconciliationService.trialBalance().isBalanced()).isTrue();
        } finally {
            policyLevers.update(original);
        }
    }

    /**
     * A provision charge is an expense (lowers the capital base) against a
     * contra-asset (lowers the net loan book CAR/NSFR are computed on) - so
     * a recession's provisioning shows up in the ratios, not just the ledger.
     */
    @Test
    void aProvisionChargeLowersTheCapitalBaseAndTheNetLoanBook() {
        Account account = openAccount();
        loanService.originateAndDisburse(account.customerId(), account.id(), LoanType.CONSUMER, new BigDecimal("1000.00"), 12);
        TreasuryRatioSnapshot before = treasuryService.computeAndPersist(LocalDate.of(2030, 4, 1));

        BigDecimal charge = new BigDecimal("250.00");
        postProvision(charge, EntryType.DEBIT);
        try {
            TreasuryRatioSnapshot after = treasuryService.computeAndPersist(LocalDate.of(2030, 4, 2));

            assertThat(before.capitalBase().subtract(after.capitalBase())).isEqualByComparingTo(charge);
            assertThat(after.loanLossProvision().subtract(before.loanLossProvision())).isEqualByComparingTo(charge);
            assertThat(after.loansReceivable()).isEqualByComparingTo(before.loansReceivable());
            assertThat(after.capitalAdequacyRatio()).isEqualByComparingTo(
                    after.capitalBase().divide(after.riskWeightedAssets(), SCALE, RoundingMode.HALF_UP));
        } finally {
            postProvision(charge, EntryType.CREDIT);
        }
        assertThat(reconciliationService.trialBalance().isBalanced()).isTrue();
    }

    /** DEBIT charges a provision (Debit PROVISION_EXPENSE / Credit LOAN_LOSS_PROVISION), CREDIT releases it. */
    private void postProvision(BigDecimal amount, EntryType expenseSide) {
        long expenseId = ledgerAccountService.getSingleton(LedgerAccountType.PROVISION_EXPENSE).id();
        long provisionId = ledgerAccountService.getSingleton(LedgerAccountType.LOAN_LOSS_PROVISION).id();
        EntryType provisionSide = expenseSide == EntryType.DEBIT ? EntryType.CREDIT : EntryType.DEBIT;
        ledgerService.post(new JournalEntryRequest(
                "Test provision " + expenseSide,
                List.of(
                        new LedgerLineRequest(expenseId, expenseSide, amount),
                        new LedgerLineRequest(provisionId, provisionSide, amount))));
    }

    @Test
    void riskWeightsFollowTheStandardisedExposureClassesNetOfProvisions() {
        BigDecimal rwa = TreasuryService.riskWeightedAssets(List.of(
                new CreditExposure(LoanType.MORTGAGE, LoanPhase.PERFORMING, new BigDecimal("1000.00"), new BigDecimal("100.00")),
                new CreditExposure(LoanType.CONSUMER, LoanPhase.UNDERPERFORMING, new BigDecimal("1000.00"), BigDecimal.ZERO),
                new CreditExposure(LoanType.BUSINESS, LoanPhase.PERFORMING, new BigDecimal("1000.00"), BigDecimal.ZERO)));
        // 900 x 35% + 1000 x 75% + 1000 x 100%
        assertThat(rwa).isEqualByComparingTo(new BigDecimal("2065.00"));
    }

    /** EBA AQT_3.2: gross Stage 3 loans over gross loans, allowances ignored; null for an empty book. */
    @Test
    void nplRatioIsGrossDefaultedLoansOverGrossLoans() {
        BigDecimal npl = TreasuryService.nonPerformingLoanRatio(List.of(
                new CreditExposure(LoanType.MORTGAGE, LoanPhase.PERFORMING, new BigDecimal("7000.00"), new BigDecimal("100.00")),
                new CreditExposure(LoanType.CONSUMER, LoanPhase.UNDERPERFORMING, new BigDecimal("2000.00"), BigDecimal.ZERO),
                new CreditExposure(LoanType.BUSINESS, LoanPhase.NON_PERFORMING, new BigDecimal("1000.00"), new BigDecimal("450.00"))));

        assertThat(npl).isEqualByComparingTo(new BigDecimal("0.10"));
        assertThat(TreasuryService.nonPerformingLoanRatio(List.of())).isNull();
    }

    /** CRR Art. 127: a defaulted exposure is 150% while provisions cover under 20% of it, 100% once they cover 20%+. */
    @Test
    void defaultedExposuresAreWeightedByProvisionCoverage() {
        CreditExposure underProvisioned =
                new CreditExposure(LoanType.MORTGAGE, LoanPhase.NON_PERFORMING, new BigDecimal("1000.00"), new BigDecimal("150.00"));
        CreditExposure provisioned =
                new CreditExposure(LoanType.CONSUMER, LoanPhase.NON_PERFORMING, new BigDecimal("1000.00"), new BigDecimal("600.00"));

        assertThat(TreasuryService.riskWeight(underProvisioned)).isEqualByComparingTo(new BigDecimal("1.50"));
        assertThat(TreasuryService.riskWeight(provisioned)).isEqualByComparingTo(BigDecimal.ONE);
    }

    /**
     * The central bank lends only against eligible collateral after haircut:
     * a shortfall far bigger than the performing loan book can back is only
     * partly covered, so the liquidity breach persists.
     */
    @Test
    void facilityDrawsAreCappedByUnusedEligibleCollateral() {
        BigDecimal collateral = loanExposureService.activeExposures().stream()
                .filter(e -> !e.defaulted())
                .map(CreditExposure::outstandingPrincipal)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .multiply(BigDecimal.ONE.subtract(TreasuryService.CREDIT_CLAIM_HAIRCUT));
        BigDecimal outstandingBefore = ledgerAccountService.singletonCreditBalance(LedgerAccountType.CENTRAL_BANK_BORROWINGS);
        BigDecimal expectedDraw = collateral.subtract(outstandingBefore).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);

        // Deposits of 10^12 need 10^11 of HQLA - far beyond any test loan book's collateral.
        TreasuryRatioSnapshot breach = new TreasuryRatioSnapshot(
                null, LocalDate.of(2097, 1, 1), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("1000000000000.00"), BigDecimal.ZERO, null, null, null, BigDecimal.ZERO, null, null, null, null, null, null);
        treasuryService.applyFeedback(breach);

        BigDecimal outstandingAfter = ledgerAccountService.singletonCreditBalance(LedgerAccountType.CENTRAL_BANK_BORROWINGS);
        assertThat(outstandingAfter.subtract(outstandingBefore)).isEqualByComparingTo(expectedDraw);
        assertThat(reconciliationService.trialBalance().isBalanced()).isTrue();
    }
}
