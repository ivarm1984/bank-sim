package io.github.ivarm1984.banksim.account;

import static io.github.ivarm1984.banksim.jooq.account.tables.Accounts.ACCOUNTS;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.jooq.DSLContext;
import org.jooq.InsertValuesStep2;
import org.springframework.stereotype.Repository;

/**
 * The account module's own data-access surface. Other modules (e.g. ledger,
 * for balance locking/updates during posting) must go through this, never a
 * raw cross-schema join - see AGENTS.md.
 */
@Repository
public class AccountRepository {

    private final DSLContext dsl;

    public AccountRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Account insert(long customerId, AccountType accountType) {
        var record = dsl.insertInto(ACCOUNTS)
                .set(ACCOUNTS.CUSTOMER_ID, customerId)
                .set(ACCOUNTS.ACCOUNT_TYPE, accountType.name())
                .returning()
                .fetchOne();
        return toAccount(record);
    }

    /** Opens many accounts in a single multi-row INSERT - see {@link AccountService#openBatch(List)}. */
    public List<Account> insertBatch(List<AccountOpenRequest> requests) {
        if (requests.isEmpty()) {
            return List.of();
        }
        InsertValuesStep2<io.github.ivarm1984.banksim.jooq.account.tables.records.AccountsRecord, Long, String> insert =
                dsl.insertInto(ACCOUNTS, ACCOUNTS.CUSTOMER_ID, ACCOUNTS.ACCOUNT_TYPE);
        for (AccountOpenRequest request : requests) {
            insert = insert.values(request.customerId(), request.accountType().name());
        }
        return insert.returning().fetch().map(AccountRepository::toAccount);
    }

    public Account findById(long id) {
        var record = dsl.selectFrom(ACCOUNTS)
                .where(ACCOUNTS.ID.eq(id))
                .fetchOne();
        if (record == null) {
            throw new NoSuchElementException("No account with id " + id);
        }
        return toAccount(record);
    }

    public List<Account> findAll() {
        return dsl.selectFrom(ACCOUNTS)
                .orderBy(ACCOUNTS.ID)
                .fetch()
                .map(AccountRepository::toAccount);
    }

    /** Page of accounts, ordered by id - for the dashboard's account list, which can't render an unbounded table at seed-scale. */
    public List<Account> findPage(int limit, int offset) {
        return dsl.selectFrom(ACCOUNTS)
                .orderBy(ACCOUNTS.ID)
                .limit(limit)
                .offset(offset)
                .fetch()
                .map(AccountRepository::toAccount);
    }

    /**
     * Locks the account row (SELECT ... FOR UPDATE) for the duration of the
     * caller's transaction. Must be called from within a transaction, before
     * any {@link #adjustBalance(long, BigDecimal)} call for the same account,
     * so concurrent postings against the same account serialize instead of
     * racing on the cached balance.
     */
    public Account lockForUpdate(long id) {
        var record = dsl.selectFrom(ACCOUNTS)
                .where(ACCOUNTS.ID.eq(id))
                .forUpdate()
                .fetchOne();
        if (record == null) {
            throw new NoSuchElementException("No account with id " + id);
        }
        return toAccount(record);
    }

    /**
     * Adjusts the cached balance by {@code delta} (positive or negative) and
     * returns the new balance. Caller must already hold the row lock via
     * {@link #lockForUpdate(long)} in the same transaction.
     */
    public BigDecimal adjustBalance(long id, BigDecimal delta) {
        var newBalance = dsl.update(ACCOUNTS)
                .set(ACCOUNTS.CURRENT_BALANCE, ACCOUNTS.CURRENT_BALANCE.add(delta))
                .where(ACCOUNTS.ID.eq(id))
                .returning(ACCOUNTS.CURRENT_BALANCE)
                .fetchOne();
        if (newBalance == null) {
            throw new NoSuchElementException("No account with id " + id);
        }
        return newBalance.getCurrentBalance();
    }

    /**
     * Bulk version of {@link #adjustBalance(long, BigDecimal)} - applies every delta as
     * one JDBC batch (one query shape, many bind sets) instead of one round trip per
     * account. Deliberately skips {@link #lockForUpdate(long)}: each delta is an atomic
     * {@code current_balance = current_balance + delta} increment at the database level,
     * so it's correct regardless of concurrent writers to the same row - unlike
     * {@link #adjustBalance(long, BigDecimal)}'s callers, which pre-lock because they
     * also need to *read* the balance first to reject a would-go-negative posting. Callers
     * of this method must not depend on that read-then-decide behavior.
     */
    public void adjustBalancesBatch(Map<Long, BigDecimal> deltasByAccountId) {
        if (deltasByAccountId.isEmpty()) {
            return;
        }
        var template = dsl.update(ACCOUNTS)
                .set(ACCOUNTS.CURRENT_BALANCE, ACCOUNTS.CURRENT_BALANCE.add((BigDecimal) null))
                .where(ACCOUNTS.ID.eq((Long) null));
        var batch = dsl.batch(template);
        for (Map.Entry<Long, BigDecimal> entry : deltasByAccountId.entrySet()) {
            batch.bind(entry.getValue(), entry.getKey());
        }
        batch.execute();
    }

    private static Account toAccount(io.github.ivarm1984.banksim.jooq.account.tables.records.AccountsRecord record) {
        return new Account(
                record.getId(),
                record.getCustomerId(),
                AccountType.valueOf(record.getAccountType()),
                record.getCurrentBalance(),
                record.getCreatedAt());
    }
}
