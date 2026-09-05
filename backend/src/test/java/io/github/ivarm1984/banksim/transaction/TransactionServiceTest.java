package io.github.ivarm1984.banksim.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.github.ivarm1984.banksim.PostgresIntegrationTest;
import io.github.ivarm1984.banksim.account.Account;
import io.github.ivarm1984.banksim.account.AccountService;
import io.github.ivarm1984.banksim.customer.Customer;
import io.github.ivarm1984.banksim.customer.CustomerService;
import io.github.ivarm1984.banksim.ledger.InsufficientFundsException;

class TransactionServiceTest extends PostgresIntegrationTest {

    @Autowired
    private TransactionService transactionService;
    @Autowired
    private CustomerService customerService;
    @Autowired
    private AccountService accountService;

    private Account openAccount() {
        Customer customer = customerService.create("Ada Lovelace");
        return accountService.open(customer.id(), "CHECKING");
    }

    @Test
    void depositCreditsAccountAndRecordsTransaction() {
        Account account = openAccount();

        Transaction transaction = transactionService.deposit(account.id(), new BigDecimal("100.00"));

        assertThat(transaction.type()).isEqualTo(TransactionType.DEPOSIT);
        assertThat(transaction.toAccountId()).isEqualTo(account.id());
        assertThat(transaction.fromAccountId()).isNull();
        assertThat(accountService.balanceOf(account.id())).isEqualByComparingTo("100.00");
    }

    @Test
    void withdrawDebitsAccountAndRecordsTransaction() {
        Account account = openAccount();
        transactionService.deposit(account.id(), new BigDecimal("100.00"));

        Transaction transaction = transactionService.withdraw(account.id(), new BigDecimal("40.00"));

        assertThat(transaction.type()).isEqualTo(TransactionType.WITHDRAWAL);
        assertThat(transaction.fromAccountId()).isEqualTo(account.id());
        assertThat(transaction.toAccountId()).isNull();
        assertThat(accountService.balanceOf(account.id())).isEqualByComparingTo("60.00");
    }

    @Test
    void withdrawMoreThanBalanceIsRejectedAndBalanceUnchanged() {
        Account account = openAccount();
        transactionService.deposit(account.id(), new BigDecimal("50.00"));

        assertThatThrownBy(() -> transactionService.withdraw(account.id(), new BigDecimal("100.00")))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(accountService.balanceOf(account.id())).isEqualByComparingTo("50.00");
    }

    @Test
    void transferMovesBalanceBetweenAccountsAndConservesTotal() {
        Account from = openAccount();
        Account to = openAccount();
        transactionService.deposit(from.id(), new BigDecimal("100.00"));

        Transaction transaction = transactionService.transfer(from.id(), to.id(), new BigDecimal("30.00"));

        assertThat(transaction.type()).isEqualTo(TransactionType.TRANSFER);
        assertThat(transaction.fromAccountId()).isEqualTo(from.id());
        assertThat(transaction.toAccountId()).isEqualTo(to.id());
        assertThat(accountService.balanceOf(from.id())).isEqualByComparingTo("70.00");
        assertThat(accountService.balanceOf(to.id())).isEqualByComparingTo("30.00");
    }

    @Test
    void transferMoreThanBalanceIsRejectedAndNeitherAccountChanges() {
        Account from = openAccount();
        Account to = openAccount();
        transactionService.deposit(from.id(), new BigDecimal("10.00"));

        assertThatThrownBy(() -> transactionService.transfer(from.id(), to.id(), new BigDecimal("50.00")))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(accountService.balanceOf(from.id())).isEqualByComparingTo("10.00");
        assertThat(accountService.balanceOf(to.id())).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
