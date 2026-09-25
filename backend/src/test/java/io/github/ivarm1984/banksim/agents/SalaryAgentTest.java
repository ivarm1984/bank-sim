package io.github.ivarm1984.banksim.agents;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.github.ivarm1984.banksim.PostgresIntegrationTest;
import io.github.ivarm1984.banksim.account.Account;
import io.github.ivarm1984.banksim.account.AccountService;
import io.github.ivarm1984.banksim.account.AccountType;
import io.github.ivarm1984.banksim.customer.CustomerService;
import io.github.ivarm1984.banksim.event.DomainEventPublisher;
import io.github.ivarm1984.banksim.eventinjector.RecessionShockService;
import io.github.ivarm1984.banksim.loan.LoanService;
import io.github.ivarm1984.banksim.transaction.TransactionService;

class SalaryAgentTest extends PostgresIntegrationTest {

    @Autowired
    private TransactionService transactionService;
    @Autowired
    private AccountService accountService;
    @Autowired
    private CustomerService customerService;
    @Autowired
    private DomainEventPublisher events;
    @Autowired
    private LoanService loanService;
    @Autowired
    private RecessionShockService recessionShockService;

    @Test
    void paysOnlyOnPaydayAndOnlyOnceEvenIfTickedTwiceTheSameDay() {
        var customer = customerService.create("Test Customer");
        Account checking = accountService.open(customer.id(), AccountType.CHECKING);
        AgentContext context = new AgentContext(transactionService, accountService, events, loanService, recessionShockService);
        SalaryAgent agent = new SalaryAgent(checking.id(), new BigDecimal("3000.00"), 1);

        LocalDateTime payday = LocalDateTime.of(2026, 1, 1, 9, 0);
        agent.onTick(payday, context);
        agent.onTick(payday.plusHours(1), context);

        assertThat(accountService.balanceOf(checking.id())).isEqualByComparingTo("3000.00");
    }

    @Test
    void doesNotPayOnNonPayday() {
        var customer = customerService.create("Test Customer");
        Account checking = accountService.open(customer.id(), AccountType.CHECKING);
        AgentContext context = new AgentContext(transactionService, accountService, events, loanService, recessionShockService);
        SalaryAgent agent = new SalaryAgent(checking.id(), new BigDecimal("3000.00"), 1);

        agent.onTick(LocalDateTime.of(2026, 1, 2, 9, 0), context);

        assertThat(accountService.balanceOf(checking.id())).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void paysAgainOnTheNextMonthsPayday() {
        var customer = customerService.create("Test Customer");
        Account checking = accountService.open(customer.id(), AccountType.CHECKING);
        AgentContext context = new AgentContext(transactionService, accountService, events, loanService, recessionShockService);
        SalaryAgent agent = new SalaryAgent(checking.id(), new BigDecimal("3000.00"), 1);

        agent.onTick(LocalDateTime.of(2026, 1, 1, 9, 0), context);
        agent.onTick(LocalDateTime.of(2026, 2, 1, 9, 0), context);

        assertThat(accountService.balanceOf(checking.id())).isEqualByComparingTo("6000.00");
    }
}
