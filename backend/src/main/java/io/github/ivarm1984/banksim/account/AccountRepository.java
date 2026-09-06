package io.github.ivarm1984.banksim.account;

import static io.github.ivarm1984.banksim.jooq.account.tables.Accounts.ACCOUNTS;

import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;

import org.jooq.DSLContext;
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

    private static Account toAccount(io.github.ivarm1984.banksim.jooq.account.tables.records.AccountsRecord record) {
        return new Account(
                record.getId(),
                record.getCustomerId(),
                AccountType.valueOf(record.getAccountType()),
                record.getCurrentBalance(),
                record.getCreatedAt());
    }
}
