package io.github.ivarm1984.banksim.ledger;

import static io.github.ivarm1984.banksim.jooq.ledger.tables.LedgerAccounts.LEDGER_ACCOUNTS;
import static io.github.ivarm1984.banksim.jooq.ledger.tables.LedgerLines.LEDGER_LINES;
import static org.jooq.impl.DSL.case_;
import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.sum;

import java.math.BigDecimal;

import org.jooq.DSLContext;
import org.springframework.stereotype.Service;

import io.github.ivarm1984.banksim.account.AccountRepository;

@Service
public class LedgerReconciliationService {

    private final DSLContext dsl;
    private final AccountRepository accountRepository;

    public LedgerReconciliationService(DSLContext dsl, AccountRepository accountRepository) {
        this.dsl = dsl;
        this.accountRepository = accountRepository;
    }

    public record ReconciliationResult(long accountId, BigDecimal cachedBalance, BigDecimal ledgerBalance) {
        public boolean isConsistent() {
            return cachedBalance.compareTo(ledgerBalance) == 0;
        }
    }

    public record TrialBalance(BigDecimal totalDebits, BigDecimal totalCredits) {
        public boolean isBalanced() {
            return totalDebits.compareTo(totalCredits) == 0;
        }
    }

    /** System-wide sum of debits vs. credits across every ledger line - the core double-entry invariant. */
    public TrialBalance trialBalance() {
        BigDecimal zero = BigDecimal.ZERO;
        var totalDebits = coalesce(sum(case_(LEDGER_LINES.ENTRY_TYPE).when(EntryType.DEBIT.name(), LEDGER_LINES.AMOUNT)), zero);
        var totalCredits = coalesce(sum(case_(LEDGER_LINES.ENTRY_TYPE).when(EntryType.CREDIT.name(), LEDGER_LINES.AMOUNT)), zero);

        return dsl.select(totalDebits, totalCredits)
                .from(LEDGER_LINES)
                .fetchOne(record -> new TrialBalance(record.get(totalDebits), record.get(totalCredits)));
    }

    /**
     * Recomputes a customer Account's balance from its CUSTOMER_LIABILITY
     * ledger account's posted lines, and compares it to the cached balance
     * on the Account row.
     */
    public ReconciliationResult reconcile(long accountId) {
        BigDecimal cachedBalance = accountRepository.findById(accountId).currentBalance();

        BigDecimal ledgerBalance = dsl.select(
                        sum(case_(LEDGER_LINES.ENTRY_TYPE)
                                .when(EntryType.CREDIT.name(), LEDGER_LINES.AMOUNT)
                                .else_(LEDGER_LINES.AMOUNT.neg())))
                .from(LEDGER_LINES)
                .join(LEDGER_ACCOUNTS).on(LEDGER_LINES.LEDGER_ACCOUNT_ID.eq(LEDGER_ACCOUNTS.ID))
                .where(LEDGER_ACCOUNTS.ACCOUNT_ID.eq(accountId))
                .and(LEDGER_ACCOUNTS.TYPE.eq(LedgerAccountType.CUSTOMER_LIABILITY.name()))
                .fetchOne(0, BigDecimal.class);

        if (ledgerBalance == null) {
            ledgerBalance = BigDecimal.ZERO;
        }

        return new ReconciliationResult(accountId, cachedBalance, ledgerBalance);
    }
}
