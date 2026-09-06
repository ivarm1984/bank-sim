package io.github.ivarm1984.banksim.treasury;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

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
import io.github.ivarm1984.banksim.ledger.LedgerAccountService;
import io.github.ivarm1984.banksim.ledger.LedgerAccountType;
import io.github.ivarm1984.banksim.ledger.LedgerReconciliationService;
import io.github.ivarm1984.banksim.loan.LoanService;
import io.github.ivarm1984.banksim.loan.LoanType;
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
        BigDecimal requiredStableFunding = snapshot.loansReceivable().multiply(TreasuryService.NSFR_LOAN_RSF_FACTOR);
        BigDecimal riskWeightedAssets = snapshot.loansReceivable().multiply(TreasuryService.LOAN_RISK_WEIGHT);

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
                null, LocalDate.of(2091, 1, 1), BigDecimal.ZERO, new BigDecimal("1000.00"), BigDecimal.ZERO,
                new BigDecimal("100000.00"), BigDecimal.ZERO, null, null, null, new BigDecimal("1000.00"), null, null, null);
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
                null, LocalDate.of(2091, 1, 2), BigDecimal.ZERO, new BigDecimal("10000000.00"), BigDecimal.ZERO,
                new BigDecimal("1000.00"), BigDecimal.ZERO, null, null, null, new BigDecimal("100.00"), null, null, null);
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
                    LocalDate.of(2092, 1, 1), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    null, null, new BigDecimal("0.500000"), BigDecimal.ZERO, null, new BigDecimal("0.010000"));

            assertThat(treasuryService.isLoanOriginationThrottled()).isTrue();
            Account account = openAccount();
            assertThatThrownBy(() -> loanService.originateAndDisburse(account.customerId(), account.id(), LoanType.CONSUMER, new BigDecimal("1000.00"), 6))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            treasuryRatioRepository.insert(
                    LocalDate.of(2092, 1, 2), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    null, null, new BigDecimal("10.000000"), BigDecimal.ZERO, null, new BigDecimal("1.000000"));
        }
        assertThat(treasuryService.isLoanOriginationThrottled()).isFalse();
    }

    @Test
    void borrowingInterestAccruesDailyAtTheMarginalLendingRate() {
        TreasuryRatioSnapshot breach = new TreasuryRatioSnapshot(
                null, LocalDate.of(2093, 1, 1), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("500000.00"), BigDecimal.ZERO, null, null, null, BigDecimal.ZERO, null, null, null);
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
}
