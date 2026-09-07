package io.github.ivarm1984.banksim.statement;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
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
import io.github.ivarm1984.banksim.transaction.TransactionService;

class StatementGenerationServiceTest extends PostgresIntegrationTest {

    @Autowired
    private StatementGenerationService statementGenerationService;
    @Autowired
    private TransactionService transactionService;
    @Autowired
    private AccountService accountService;
    @Autowired
    private CustomerService customerService;

    private Account openAccount() {
        Customer customer = customerService.create("Ada Lovelace");
        return accountService.open(customer.id(), AccountType.CHECKING);
    }

    @Test
    void firstStatementDefaultsOpeningBalanceToClosingBalance() {
        Account account = openAccount();
        transactionService.deposit(account.id(), new BigDecimal("100.00"));

        Statement statement = statementGenerationService.generateForAccount(account.id(), LocalDate.of(2026, 1, 1));

        assertThat(statement.openingBalance()).isEqualByComparingTo("100.00");
        assertThat(statement.closingBalance()).isEqualByComparingTo("100.00");
    }

    @Test
    void nextDaysOpeningBalanceEqualsPreviousDaysClosingBalance() {
        Account account = openAccount();
        transactionService.deposit(account.id(), new BigDecimal("100.00"));
        Statement day1 = statementGenerationService.generateForAccount(account.id(), LocalDate.of(2026, 1, 1));

        transactionService.deposit(account.id(), new BigDecimal("50.00"));
        Statement day2 = statementGenerationService.generateForAccount(account.id(), LocalDate.of(2026, 1, 2));

        assertThat(day2.openingBalance()).isEqualByComparingTo(day1.closingBalance());
        assertThat(day2.closingBalance()).isEqualByComparingTo("150.00");
    }

    @Test
    void chunkedGenerationHandlesFirstAndSubsequentStatementsAcrossAccountsInOneCall() {
        Account withHistory = openAccount();
        transactionService.deposit(withHistory.id(), new BigDecimal("100.00"));
        statementGenerationService.generateForAccount(withHistory.id(), LocalDate.of(2026, 1, 1));
        transactionService.deposit(withHistory.id(), new BigDecimal("50.00"));
        Account brandNew = openAccount();
        transactionService.deposit(brandNew.id(), new BigDecimal("25.00"));

        List<Statement> statements = statementGenerationService.generateForChunk(
                List.of(accountService.findById(withHistory.id()), accountService.findById(brandNew.id())),
                LocalDate.of(2026, 1, 2));

        Statement withHistoryStatement = statements.stream().filter(s -> s.accountId().equals(withHistory.id())).findFirst().orElseThrow();
        Statement brandNewStatement = statements.stream().filter(s -> s.accountId().equals(brandNew.id())).findFirst().orElseThrow();
        assertThat(withHistoryStatement.openingBalance()).isEqualByComparingTo("100.00");
        assertThat(withHistoryStatement.closingBalance()).isEqualByComparingTo("150.00");
        assertThat(brandNewStatement.openingBalance()).isEqualByComparingTo("25.00");
        assertThat(brandNewStatement.closingBalance()).isEqualByComparingTo("25.00");
    }
}
