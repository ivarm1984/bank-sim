package io.github.ivarm1984.banksim.ledger;

import static io.github.ivarm1984.banksim.jooq.ledger.tables.LedgerAccounts.LEDGER_ACCOUNTS;

import java.util.NoSuchElementException;

import org.jooq.DSLContext;
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
                record.getName(),
                record.getCreatedAt());
    }
}
