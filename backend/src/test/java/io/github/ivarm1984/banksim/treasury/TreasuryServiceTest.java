package io.github.ivarm1984.banksim.treasury;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.github.ivarm1984.banksim.PostgresIntegrationTest;
import io.github.ivarm1984.banksim.account.Account;
import io.github.ivarm1984.banksim.account.AccountService;
import io.github.ivarm1984.banksim.account.AccountType;
import io.github.ivarm1984.banksim.customer.Customer;
import io.github.ivarm1984.banksim.customer.CustomerService;
import io.github.ivarm1984.banksim.loan.LoanService;
import io.github.ivarm1984.banksim.transaction.TransactionService;

class TreasuryServiceTest extends PostgresIntegrationTest {

    private static final int SCALE = 6;

    @Autowired
    private TreasuryService treasuryService;
    @Autowired
    private AccountService accountService;
    @Autowired
    private CustomerService customerService;
    @Autowired
    private TransactionService transactionService;
    @Autowired
    private LoanService loanService;

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
        loanService.originateAndDisburse(account.customerId(), account.id(), new BigDecimal("1000.00"), 12);

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
        loanService.originateAndDisburse(account.customerId(), account.id(), principal, 12);

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
}
