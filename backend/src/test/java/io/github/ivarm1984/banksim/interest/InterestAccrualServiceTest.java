package io.github.ivarm1984.banksim.interest;

import static org.assertj.core.api.Assertions.assertThat;

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
import io.github.ivarm1984.banksim.customer.Customer;
import io.github.ivarm1984.banksim.customer.CustomerService;
import io.github.ivarm1984.banksim.ledger.LedgerReconciliationService;
import io.github.ivarm1984.banksim.transaction.TransactionService;

class InterestAccrualServiceTest extends PostgresIntegrationTest {

    @Autowired
    private InterestAccrualService interestAccrualService;
    @Autowired
    private TransactionService transactionService;
    @Autowired
    private AccountService accountService;
    @Autowired
    private CustomerService customerService;
    @Autowired
    private LedgerReconciliationService reconciliationService;

    private Account openAccount(AccountType type) {
        Customer customer = customerService.create("Ada Lovelace");
        return accountService.open(customer.id(), type);
    }

    @Test
    void accruesInterestOnSavingsBalanceAtSeededRateAndKeepsLedgerBalanced() {
        Account account = openAccount(AccountType.SAVINGS);
        transactionService.deposit(account.id(), new BigDecimal("10000.00"));
        BigDecimal annualRate = new BigDecimal("0.0150");
        BigDecimal expectedAmount = new BigDecimal("10000.00")
                .multiply(annualRate)
                .divide(new BigDecimal("365"), 10, RoundingMode.HALF_UP)
                .setScale(2, RoundingMode.HALF_UP);

        InterestAccrual accrual = interestAccrualService.accrueForAccount(account.id(), LocalDate.of(2026, 1, 1));

        assertThat(accrual.amount()).isEqualByComparingTo(expectedAmount);
        assertThat(accrual.annualRate()).isEqualByComparingTo(annualRate);
        assertThat(accrual.journalEntryId()).isNotNull();
        assertThat(accountService.balanceOf(account.id())).isEqualByComparingTo(
                new BigDecimal("10000.00").add(expectedAmount));
        assertThat(reconciliationService.reconcile(account.id()).isConsistent()).isTrue();
    }

    @Test
    void doesNotPostAZeroRateAccrualButStillRecordsIt() {
        Account account = openAccount(AccountType.CHECKING);
        transactionService.deposit(account.id(), new BigDecimal("500.00"));

        InterestAccrual accrual = interestAccrualService.accrueForAccount(account.id(), LocalDate.of(2026, 1, 1));

        assertThat(accrual.amount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(accrual.journalEntryId()).isNull();
        assertThat(accountService.balanceOf(account.id())).isEqualByComparingTo("500.00");
    }

    @Test
    void secondAccrualOnTheSameDateForTheSameAccountIsRejected() {
        Account account = openAccount(AccountType.SAVINGS);
        transactionService.deposit(account.id(), new BigDecimal("1000.00"));
        LocalDate date = LocalDate.of(2026, 1, 1);
        interestAccrualService.accrueForAccount(account.id(), date);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> interestAccrualService.accrueForAccount(account.id(), date))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void chunkedAccrualSharesOneJournalEntryAcrossAccountsAndKeepsLedgerBalanced() {
        Account savings = openAccount(AccountType.SAVINGS);
        transactionService.deposit(savings.id(), new BigDecimal("10000.00"));
        Account checking = openAccount(AccountType.CHECKING); // 0% seeded rate - no ledger line for this one
        transactionService.deposit(checking.id(), new BigDecimal("500.00"));
        LocalDate date = LocalDate.of(2026, 1, 1);
        BigDecimal savingsRate = new BigDecimal("0.0150");
        BigDecimal expectedSavingsAmount = new BigDecimal("10000.00")
                .multiply(savingsRate)
                .divide(new BigDecimal("365"), 10, RoundingMode.HALF_UP)
                .setScale(2, RoundingMode.HALF_UP);

        List<InterestAccrual> accruals = interestAccrualService.accrueForChunk(
                List.of(accountService.findById(savings.id()), accountService.findById(checking.id())),
                interestAccrualService.currentRates(),
                date);

        InterestAccrual savingsAccrual = accruals.stream().filter(a -> a.accountId().equals(savings.id())).findFirst().orElseThrow();
        InterestAccrual checkingAccrual = accruals.stream().filter(a -> a.accountId().equals(checking.id())).findFirst().orElseThrow();
        assertThat(savingsAccrual.amount()).isEqualByComparingTo(expectedSavingsAmount);
        assertThat(savingsAccrual.journalEntryId()).isNotNull();
        assertThat(checkingAccrual.amount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(checkingAccrual.journalEntryId()).isNull();
        assertThat(accountService.balanceOf(savings.id())).isEqualByComparingTo(new BigDecimal("10000.00").add(expectedSavingsAmount));
        assertThat(accountService.balanceOf(checking.id())).isEqualByComparingTo("500.00");
        assertThat(reconciliationService.reconcile(savings.id()).isConsistent()).isTrue();
        assertThat(reconciliationService.reconcile(checking.id()).isConsistent()).isTrue();
    }

    @Test
    void chunkedAccrualWithNoAccruingAccountsPostsNoJournalEntryButStillRecordsAccruals() {
        Account checkingA = openAccount(AccountType.CHECKING);
        Account checkingB = openAccount(AccountType.CHECKING);
        LocalDate date = LocalDate.of(2026, 1, 1);

        List<InterestAccrual> accruals = interestAccrualService.accrueForChunk(
                List.of(accountService.findById(checkingA.id()), accountService.findById(checkingB.id())),
                interestAccrualService.currentRates(),
                date);

        assertThat(accruals).hasSize(2);
        assertThat(accruals).allSatisfy(a -> {
            assertThat(a.amount()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(a.journalEntryId()).isNull();
        });
    }
}
