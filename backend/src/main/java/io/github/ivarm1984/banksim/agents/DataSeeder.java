package io.github.ivarm1984.banksim.agents;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import io.github.ivarm1984.banksim.account.Account;
import io.github.ivarm1984.banksim.account.AccountOpenRequest;
import io.github.ivarm1984.banksim.account.AccountService;
import io.github.ivarm1984.banksim.account.AccountType;
import io.github.ivarm1984.banksim.customer.Customer;
import io.github.ivarm1984.banksim.customer.CustomerService;

/**
 * Seeds demo customers/accounts and their agents on first startup only -
 * skipped once any customer already exists, so re-running {@code bootRun}
 * doesn't duplicate the demo data.
 *
 * <p>The first {@link #DEMO_CUSTOMER_NAMES} are hand-named for continuity/
 * manual testing; the rest are generated up to {@link #SEED_CUSTOMER_COUNT}
 * and created via the batch-insert paths on {@link CustomerService} and
 * {@link AccountService} - the per-row {@code create}/{@code open} calls
 * used for the named customers would mean tens of thousands of individual
 * round trips at this scale.
 */
@Component
public class DataSeeder implements ApplicationRunner {

    private static final List<String> DEMO_CUSTOMER_NAMES =
            List.of("Ada Lovelace", "Alan Turing", "Grace Hopper", "Katherine Johnson");

    /** Total simulated customers - easy to turn down for a lighter local run. */
    private static final int SEED_CUSTOMER_COUNT = 10_000;
    private static final double BORROWER_SHARE = 0.30;
    private static final int BATCH_SIZE = 1000;
    private static final long AGENT_RANDOM_SEED = 42;

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

        List<Customer> customers = seedCustomers();
        List<Account> checkingAccounts = seedAccounts(customers, AccountType.CHECKING);
        seedAccounts(customers, AccountType.SAVINGS); // symmetry with the dashboard; not used by any agent

        Random agentSelector = new Random(AGENT_RANDOM_SEED);
        for (int i = 0; i < customers.size(); i++) {
            registerAgentsFor(customers.get(i).id(), checkingAccounts.get(i).id(), i, agentSelector);
        }
    }

    private List<Customer> seedCustomers() {
        List<Customer> customers = new ArrayList<>();
        for (String name : DEMO_CUSTOMER_NAMES) {
            customers.add(customerService.create(name));
        }
        List<String> generatedNames = new ArrayList<>();
        for (int i = customers.size(); i < SEED_CUSTOMER_COUNT; i++) {
            generatedNames.add(String.format("Customer %05d", i + 1));
        }
        for (List<String> chunk : partition(generatedNames, BATCH_SIZE)) {
            customers.addAll(customerService.createBatch(chunk));
        }
        return customers;
    }

    /** Same order as {@code customers} - relies on a single multi-row INSERT ... RETURNING preserving VALUES order (true for Postgres). */
    private List<Account> seedAccounts(List<Customer> customers, AccountType accountType) {
        List<AccountOpenRequest> requests = customers.stream().map(c -> new AccountOpenRequest(c.id(), accountType)).toList();
        List<Account> accounts = new ArrayList<>();
        for (List<AccountOpenRequest> chunk : partition(requests, BATCH_SIZE)) {
            accounts.addAll(accountService.openBatch(chunk));
        }
        return accounts;
    }

    private void registerAgentsFor(long customerId, long checkingAccountId, int index, Random agentSelector) {
        if (agentSelector.nextDouble() < BORROWER_SHARE) {
            // A borrower needs income to service its debt.
            agentScheduler.register(new SalaryAgent(checkingAccountId, new BigDecimal("3000.00"), 1));
            agentScheduler.register(new BorrowerAgent(customerId, checkingAccountId, index));
            return;
        }
        Agent agent = switch (index % 3) {
            case 0 -> new SalaryAgent(checkingAccountId, new BigDecimal("3000.00"), 1);
            case 1 -> new BillPayAgent(checkingAccountId, new BigDecimal("150.00"), 5);
            default -> new RandomSpenderAgent(checkingAccountId, index, 0.3, new BigDecimal("5.00"), new BigDecimal("80.00"));
        };
        agentScheduler.register(agent);
    }

    private static <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> chunks = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            chunks.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return chunks;
    }
}
