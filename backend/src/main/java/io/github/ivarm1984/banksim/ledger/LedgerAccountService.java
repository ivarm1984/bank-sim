package io.github.ivarm1984.banksim.ledger;

import static io.github.ivarm1984.banksim.jooq.ledger.tables.LedgerAccounts.LEDGER_ACCOUNTS;
import static io.github.ivarm1984.banksim.jooq.ledger.tables.LedgerLines.LEDGER_LINES;
import static org.jooq.impl.DSL.case_;
import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.sum;

import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;

import org.jooq.DSLContext;
import org.jooq.InsertValuesStep3;
import org.springframework.stereotype.Service;

@Service
public class LedgerAccountService {

    private final DSLContext dsl;

    public LedgerAccountService(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** Looks up one of the singleton chart-of-accounts entries seeded at migration time. */
    public LedgerAccount getSingleton(LedgerAccountType type) {
        var record = dsl.selectFrom(LEDGER_ACCOUNTS)
                .where(LEDGER_ACCOUNTS.TYPE.eq(type.name()))
                .and(LEDGER_ACCOUNTS.ACCOUNT_ID.isNull())
                .fetchOne();
        if (record == null) {
            throw new NoSuchElementException("No singleton ledger account of type " + type);
        }
        return toLedgerAccount(record);
    }

    /** Creates the one CUSTOMER_LIABILITY ledger account for a newly opened customer Account. */
    public LedgerAccount createCustomerLiabilityAccount(long accountId) {
        var record = dsl.insertInto(LEDGER_ACCOUNTS)
                .set(LEDGER_ACCOUNTS.TYPE, LedgerAccountType.CUSTOMER_LIABILITY.name())
                .set(LEDGER_ACCOUNTS.ACCOUNT_ID, accountId)
                .set(LEDGER_ACCOUNTS.NAME, "Customer liability for account " + accountId)
                .returning()
                .fetchOne();
        return toLedgerAccount(record);
    }

    /** Bulk version of {@link #createCustomerLiabilityAccount(long)} - one multi-row INSERT for many accounts. */
    public void createCustomerLiabilityAccountsBatch(List<Long> accountIds) {
        if (accountIds.isEmpty()) {
            return;
        }
        InsertValuesStep3<io.github.ivarm1984.banksim.jooq.ledger.tables.records.LedgerAccountsRecord, String, Long, String> insert =
                dsl.insertInto(LEDGER_ACCOUNTS, LEDGER_ACCOUNTS.TYPE, LEDGER_ACCOUNTS.ACCOUNT_ID, LEDGER_ACCOUNTS.NAME);
        for (Long accountId : accountIds) {
            insert = insert.values(LedgerAccountType.CUSTOMER_LIABILITY.name(), accountId, "Customer liability for account " + accountId);
        }
        insert.execute();
    }

    /** Looks up the one CUSTOMER_LIABILITY ledger account backing a customer Account. */
    public LedgerAccount findCustomerLiabilityAccount(long accountId) {
        var record = dsl.selectFrom(LEDGER_ACCOUNTS)
                .where(LEDGER_ACCOUNTS.ACCOUNT_ID.eq(accountId))
                .and(LEDGER_ACCOUNTS.TYPE.eq(LedgerAccountType.CUSTOMER_LIABILITY.name()))
                .fetchOne();
        if (record == null) {
            throw new NoSuchElementException("No customer liability ledger account for account " + accountId);
        }
        return toLedgerAccount(record);
    }

    /** Creates the one LOAN_RECEIVABLE ledger account for a newly disbursed Loan. */
    public LedgerAccount createLoanReceivableAccount(long loanId) {
        var record = dsl.insertInto(LEDGER_ACCOUNTS)
                .set(LEDGER_ACCOUNTS.TYPE, LedgerAccountType.LOAN_RECEIVABLE.name())
                .set(LEDGER_ACCOUNTS.LOAN_ID, loanId)
                .set(LEDGER_ACCOUNTS.NAME, "Loan receivable for loan " + loanId)
                .returning()
                .fetchOne();
        return toLedgerAccount(record);
    }

    /** Looks up the one LOAN_RECEIVABLE ledger account backing a Loan. */
    public LedgerAccount findLoanReceivableAccount(long loanId) {
        var record = dsl.selectFrom(LEDGER_ACCOUNTS)
                .where(LEDGER_ACCOUNTS.LOAN_ID.eq(loanId))
                .and(LEDGER_ACCOUNTS.TYPE.eq(LedgerAccountType.LOAN_RECEIVABLE.name()))
                .fetchOne();
        if (record == null) {
            throw new NoSuchElementException("No loan receivable ledger account for loan " + loanId);
        }
        return toLedgerAccount(record);
    }

    /** Every ledger account with its total debits/credits posted so far, for the ledger inspector view. */
    public List<LedgerAccountBalance> findAllWithBalances() {
        BigDecimal zero = BigDecimal.ZERO;
        var totalDebits = coalesce(sum(case_(LEDGER_LINES.ENTRY_TYPE).when(EntryType.DEBIT.name(), LEDGER_LINES.AMOUNT)), zero);
        var totalCredits = coalesce(sum(case_(LEDGER_LINES.ENTRY_TYPE).when(EntryType.CREDIT.name(), LEDGER_LINES.AMOUNT)), zero);

        return dsl.select(LEDGER_ACCOUNTS.ID, LEDGER_ACCOUNTS.TYPE, LEDGER_ACCOUNTS.ACCOUNT_ID, LEDGER_ACCOUNTS.LOAN_ID, LEDGER_ACCOUNTS.NAME, totalDebits, totalCredits)
                .from(LEDGER_ACCOUNTS)
                .leftJoin(LEDGER_LINES).on(LEDGER_LINES.LEDGER_ACCOUNT_ID.eq(LEDGER_ACCOUNTS.ID))
                .groupBy(LEDGER_ACCOUNTS.ID, LEDGER_ACCOUNTS.TYPE, LEDGER_ACCOUNTS.ACCOUNT_ID, LEDGER_ACCOUNTS.LOAN_ID, LEDGER_ACCOUNTS.NAME)
                .orderBy(LEDGER_ACCOUNTS.ID)
                .fetch(record -> new LedgerAccountBalance(
                        record.get(LEDGER_ACCOUNTS.ID),
                        LedgerAccountType.valueOf(record.get(LEDGER_ACCOUNTS.TYPE)),
                        record.get(LEDGER_ACCOUNTS.ACCOUNT_ID),
                        record.get(LEDGER_ACCOUNTS.LOAN_ID),
                        record.get(LEDGER_ACCOUNTS.NAME),
                        record.get(totalDebits),
                        record.get(totalCredits)));
    }

    /** Current credit-normal balance (credits minus debits) of a singleton liability/equity/income account, e.g. an outstanding facility balance. */
    public BigDecimal singletonCreditBalance(LedgerAccountType type) {
        return findAllWithBalances().stream()
                .filter(b -> b.type() == type)
                .map(b -> b.totalCredits().subtract(b.totalDebits()))
                .findFirst()
                .orElse(BigDecimal.ZERO);
    }

    /** True if any journal lines have ever been posted against this ledger account. */
    public boolean hasAnyLedgerLines(long ledgerAccountId) {
        return dsl.fetchExists(dsl.selectFrom(LEDGER_LINES).where(LEDGER_LINES.LEDGER_ACCOUNT_ID.eq(ledgerAccountId)));
    }

    public LedgerAccount findById(long id) {
        var record = dsl.selectFrom(LEDGER_ACCOUNTS)
                .where(LEDGER_ACCOUNTS.ID.eq(id))
                .fetchOne();
        if (record == null) {
            throw new NoSuchElementException("No ledger account with id " + id);
        }
        return toLedgerAccount(record);
    }

    private static LedgerAccount toLedgerAccount(
            io.github.ivarm1984.banksim.jooq.ledger.tables.records.LedgerAccountsRecord record) {
        return new LedgerAccount(
                record.getId(),
                LedgerAccountType.valueOf(record.getType()),
                record.getAccountId(),
                record.getLoanId(),
                record.getName(),
                record.getCreatedAt());
    }
}
