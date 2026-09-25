package io.github.ivarm1984.banksim.agents;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
import io.github.ivarm1984.banksim.customer.CreditGrade;
import io.github.ivarm1984.banksim.customer.Customer;
import io.github.ivarm1984.banksim.customer.CustomerService;
import io.github.ivarm1984.banksim.customer.NewCustomer;

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
 *
 * <p>Every customer gets a credit profile - a {@link CreditGrade} drawn from
 * {@link #GRADE_SHARES} and a monthly income around its grade's typical
 * income - from its own seeded generator, so the profiles don't shift which
 * agents each customer gets. Salary agents pay in the customer's income.
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
    private static final long PROFILE_RANDOM_SEED = 7;

    /** Share of customers per grade, A..E - most borrowers are decent, a thin tail is subprime. */
    private static final double[] GRADE_SHARES = {0.20, 0.30, 0.25, 0.15, 0.10};
    /** Typical net monthly income per grade, A..E - better grades tend to earn more; each customer gets +/-40% around it. */
    private static final int[] GRADE_TYPICAL_INCOME = {4800, 3900, 3200, 2600, 2100};

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
            registerAgentsFor(customers.get(i), checkingAccounts.get(i).id(), i, agentSelector);
        }
    }

    private List<Customer> seedCustomers() {
        Random profiles = new Random(PROFILE_RANDOM_SEED);
        List<Customer> customers = new ArrayList<>();
        for (String name : DEMO_CUSTOMER_NAMES) {
            customers.add(customerService.create(newCustomer(name, profiles)));
        }
        List<NewCustomer> generated = new ArrayList<>();
        for (int i = customers.size(); i < SEED_CUSTOMER_COUNT; i++) {
            generated.add(newCustomer(String.format("Customer %05d", i + 1), profiles));
        }
        for (List<NewCustomer> chunk : partition(generated, BATCH_SIZE)) {
            customers.addAll(customerService.createBatch(chunk));
        }
        return customers;
    }

    private static NewCustomer newCustomer(String fullName, Random profiles) {
        int grade = pickGrade(profiles.nextDouble());
        double income = GRADE_TYPICAL_INCOME[grade] * (0.6 + 0.8 * profiles.nextDouble());
        BigDecimal monthlyIncome = BigDecimal.valueOf(Math.round(income / 10) * 10L).setScale(2, RoundingMode.UNNECESSARY);
        return new NewCustomer(fullName, CreditGrade.values()[grade], monthlyIncome);
    }

    private static int pickGrade(double roll) {
        double cumulative = 0;
        for (int i = 0; i < GRADE_SHARES.length - 1; i++) {
            cumulative += GRADE_SHARES[i];
            if (roll < cumulative) {
                return i;
            }
        }
        return GRADE_SHARES.length - 1;
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

    private void registerAgentsFor(Customer customer, long checkingAccountId, int index, Random agentSelector) {
        if (agentSelector.nextDouble() < BORROWER_SHARE) {
            // A borrower needs income to service its debt.
            agentScheduler.register(new SalaryAgent(checkingAccountId, customer.monthlyIncome(), 1));
            agentScheduler.register(new BorrowerAgent(customer.id(), checkingAccountId, index));
            return;
        }
        Agent agent = switch (index % 3) {
            case 0 -> new SalaryAgent(checkingAccountId, customer.monthlyIncome(), 1);
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
