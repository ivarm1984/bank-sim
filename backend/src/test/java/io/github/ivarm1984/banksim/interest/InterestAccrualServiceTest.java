package io.github.ivarm1984.banksim.interest;

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
}
