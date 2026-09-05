package io.github.ivarm1984.banksim.transaction;

import static io.github.ivarm1984.banksim.jooq.transaction.tables.Transactions.TRANSACTIONS;

import java.math.BigDecimal;
import java.util.List;

import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
public class TransactionRepository {

    private final DSLContext dsl;

    public TransactionRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Transaction insert(TransactionType type, Long fromAccountId, Long toAccountId, BigDecimal amount, long journalEntryId) {
        var record = dsl.insertInto(TRANSACTIONS)
                .set(TRANSACTIONS.TYPE, type.name())
                .set(TRANSACTIONS.FROM_ACCOUNT_ID, fromAccountId)
                .set(TRANSACTIONS.TO_ACCOUNT_ID, toAccountId)
                .set(TRANSACTIONS.AMOUNT, amount)
                .set(TRANSACTIONS.JOURNAL_ENTRY_ID, journalEntryId)
                .returning()
                .fetchOne();
        return toTransaction(record);
    }

    public List<Transaction> findAll() {
        return dsl.selectFrom(TRANSACTIONS)
                .orderBy(TRANSACTIONS.ID)
                .fetch()
                .map(TransactionRepository::toTransaction);
    }

    private static Transaction toTransaction(io.github.ivarm1984.banksim.jooq.transaction.tables.records.TransactionsRecord record) {
        return new Transaction(
                record.getId(),
                TransactionType.valueOf(record.getType()),
                record.getFromAccountId(),
                record.getToAccountId(),
                record.getAmount(),
                record.getJournalEntryId(),
                record.getCreatedAt());
    }
}
