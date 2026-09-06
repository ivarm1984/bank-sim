package io.github.ivarm1984.banksim.agents;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import io.github.ivarm1984.banksim.account.Account;
import io.github.ivarm1984.banksim.account.AccountService;
import io.github.ivarm1984.banksim.account.AccountType;
import io.github.ivarm1984.banksim.customer.CustomerService;

/**
 * Seeds demo customers/accounts and their agents on first startup only -
 * skipped once any customer already exists, so re-running {@code bootRun}
 * doesn't duplicate the demo data.
 */
@Component
public class DataSeeder implements ApplicationRunner {

    private static final List<String> DEMO_CUSTOMER_NAMES =
            List.of("Ada Lovelace", "Alan Turing", "Grace Hopper", "Katherine Johnson");

    private final CustomerService customerService;
    private final AccountService accountService;
    private final AgentScheduler agentScheduler;

    public DataSeeder(CustomerService customerService, AccountService accountService, AgentScheduler agentScheduler) {
        this.customerService = customerService;
        this.accountService = accountService;
        this.agentScheduler = agentScheduler;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!customerService.findAll().isEmpty()) {
            return;
        }

        for (int i = 0; i < DEMO_CUSTOMER_NAMES.size(); i++) {
            var customer = customerService.create(DEMO_CUSTOMER_NAMES.get(i));
            Account checking = accountService.open(customer.id(), AccountType.CHECKING);
            accountService.open(customer.id(), AccountType.SAVINGS);
            registerAgent(checking.id(), i % 3, i);
        }
    }

    private void registerAgent(long checkingAccountId, int agentTypeIndex, long randomSeed) {
        Agent agent = switch (agentTypeIndex) {
            case 0 -> new SalaryAgent(checkingAccountId, new BigDecimal("3000.00"), 1);
            case 1 -> new BillPayAgent(checkingAccountId, new BigDecimal("150.00"), 5);
            default -> new RandomSpenderAgent(checkingAccountId, randomSeed, 0.3, new BigDecimal("5.00"), new BigDecimal("80.00"));
        };
        agentScheduler.register(agent);
    }
}
