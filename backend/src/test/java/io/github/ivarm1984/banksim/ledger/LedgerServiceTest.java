package io.github.ivarm1984.banksim.ledger;

import static io.github.ivarm1984.banksim.jooq.ledger.tables.LedgerAccounts.LEDGER_ACCOUNTS;
import static io.github.ivarm1984.banksim.jooq.ledger.tables.LedgerLines.LEDGER_LINES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.jooq.impl.DSL.case_;
import static org.jooq.impl.DSL.sum;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.github.ivarm1984.banksim.PostgresIntegrationTest;
import io.github.ivarm1984.banksim.account.Account;
import io.github.ivarm1984.banksim.account.AccountService;
import io.github.ivarm1984.banksim.account.AccountType;
import io.github.ivarm1984.banksim.customer.Customer;
import io.github.ivarm1984.banksim.customer.CustomerService;

class LedgerServiceTest extends PostgresIntegrationTest {

    @Autowired
    private LedgerService ledgerService;
    @Autowired
    private LedgerAccountService ledgerAccountService;
    @Autowired
    private LedgerReconciliationService reconciliationService;
    @Autowired
    private CustomerService customerService;
    @Autowired
    private AccountService accountService;
    @Autowired
    private DSLContext dsl;

    private Account openAccount() {
        Customer customer = customerService.create("Ada Lovelace");
        return accountService.open(customer.id(), AccountType.CHECKING);
    }

    @Test
    void postingBalancedEntryUpdatesAccountBalanceAndKeepsLedgerBalanced() {
        Account account = openAccount();
        long bankCashId = ledgerAccountService.getSingleton(LedgerAccountType.BANK_CASH).id();
        long liabilityId = customerLiabilityLedgerAccountId(account.id());

        ledgerService.post(new JournalEntryRequest(
                "Deposit",
                List.of(
                        new LedgerLineRequest(bankCashId, EntryType.DEBIT, new BigDecimal("100.00")),
                        new LedgerLineRequest(liabilityId, EntryType.CREDIT, new BigDecimal("100.00")))));

        assertThat(accountService.balanceOf(account.id())).isEqualByComparingTo("100.00");
        assertThat(trialBalance()).isEqualByComparingTo(BigDecimal.ZERO);

        var reconciliation = reconciliationService.reconcile(account.id());
        assertThat(reconciliation.isConsistent()).isTrue();
    }

    @Test
    void unbalancedEntryIsRejectedAndNothingIsWritten() {
        Account account = openAccount();
        long bankCashId = ledgerAccountService.getSingleton(LedgerAccountType.BANK_CASH).id();
        long liabilityId = customerLiabilityLedgerAccountId(account.id());

        assertThatThrownBy(() -> ledgerService.post(new JournalEntryRequest(
                        "Bad entry",
                        List.of(
                                new LedgerLineRequest(bankCashId, EntryType.DEBIT, new BigDecimal("100.00")),
                                new LedgerLineRequest(liabilityId, EntryType.CREDIT, new BigDecimal("50.00"))))))
                .isInstanceOf(UnbalancedJournalEntryException.class);

        assertThat(accountService.balanceOf(account.id())).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(trialBalance()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void concurrentPostingsAgainstSameAccountDoNotLoseUpdates() throws InterruptedException {
        Account account = openAccount();
        long bankCashId = ledgerAccountService.getSingleton(LedgerAccountType.BANK_CASH).id();
        long liabilityId = customerLiabilityLedgerAccountId(account.id());

        int postings = 20;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch done = new CountDownLatch(postings);
        for (int i = 0; i < postings; i++) {
            pool.submit(() -> {
                try {
                    ledgerService.post(new JournalEntryRequest(
                            "Concurrent deposit",
                            List.of(
                                    new LedgerLineRequest(bankCashId, EntryType.DEBIT, new BigDecimal("1.00")),
                                    new LedgerLineRequest(liabilityId, EntryType.CREDIT, new BigDecimal("1.00")))));
                } finally {
                    done.countDown();
                }
            });
        }
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(accountService.balanceOf(account.id())).isEqualByComparingTo(new BigDecimal("20.00"));
        assertThat(reconciliationService.reconcile(account.id()).isConsistent()).isTrue();
    }

    private long customerLiabilityLedgerAccountId(long accountId) {
        return dsl.select(LEDGER_ACCOUNTS.ID)
                .from(LEDGER_ACCOUNTS)
                .where(LEDGER_ACCOUNTS.ACCOUNT_ID.eq(accountId))
                .fetchOne(LEDGER_ACCOUNTS.ID);
    }

    /** SUM(debits) - SUM(credits) across every ledger line ever posted; must always net to zero. */
    private BigDecimal trialBalance() {
        BigDecimal result = dsl.select(
                        sum(case_(LEDGER_LINES.ENTRY_TYPE)
                                .when(EntryType.DEBIT.name(), LEDGER_LINES.AMOUNT)
                                .else_(LEDGER_LINES.AMOUNT.neg())))
                .from(LEDGER_LINES)
                .fetchOne(0, BigDecimal.class);
        return result == null ? BigDecimal.ZERO : result;
    }
}
