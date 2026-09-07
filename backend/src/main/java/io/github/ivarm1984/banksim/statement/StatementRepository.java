package io.github.ivarm1984.banksim.statement;

import static io.github.ivarm1984.banksim.jooq.statement.tables.Statements.STATEMENTS;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jooq.DSLContext;
import org.jooq.InsertValuesStep4;
import org.springframework.stereotype.Repository;

import io.github.ivarm1984.banksim.jooq.statement.tables.records.StatementsRecord;

@Repository
public class StatementRepository {

    private final DSLContext dsl;

    public StatementRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Statement insert(long accountId, LocalDate statementDate, BigDecimal openingBalance, BigDecimal closingBalance) {
        var record = dsl.insertInto(STATEMENTS)
                .set(STATEMENTS.ACCOUNT_ID, accountId)
                .set(STATEMENTS.STATEMENT_DATE, statementDate)
                .set(STATEMENTS.OPENING_BALANCE, openingBalance)
                .set(STATEMENTS.CLOSING_BALANCE, closingBalance)
                .returning()
                .fetchOne();
        return toStatement(record);
    }

    /** The account's most recently dated statement, if any - the source of the next statement's opening balance. */
    public Optional<Statement> findMostRecent(long accountId) {
        var record = dsl.selectFrom(STATEMENTS)
                .where(STATEMENTS.ACCOUNT_ID.eq(accountId))
                .orderBy(STATEMENTS.STATEMENT_DATE.desc())
                .limit(1)
                .fetchOne();
        return Optional.ofNullable(record).map(StatementRepository::toStatement);
    }

    /** One statement row to write, for {@link #insertBatch}. */
    public record NewStatement(long accountId, LocalDate statementDate, BigDecimal openingBalance, BigDecimal closingBalance) {}

    /** Bulk version of {@link #insert} - one multi-row INSERT for a whole chunk of accounts. */
    public List<Statement> insertBatch(List<NewStatement> statements) {
        if (statements.isEmpty()) {
            return List.of();
        }
        InsertValuesStep4<StatementsRecord, Long, LocalDate, BigDecimal, BigDecimal> insert = dsl.insertInto(
                STATEMENTS, STATEMENTS.ACCOUNT_ID, STATEMENTS.STATEMENT_DATE, STATEMENTS.OPENING_BALANCE, STATEMENTS.CLOSING_BALANCE);
        for (NewStatement statement : statements) {
            insert = insert.values(statement.accountId(), statement.statementDate(), statement.openingBalance(), statement.closingBalance());
        }
        return insert.returning().fetch().map(StatementRepository::toStatement);
    }

    /**
     * Bulk version of {@link #findMostRecent} - the most recent closing balance for many
     * accounts at once, keyed by account id; an account with no prior statement is simply
     * absent from the result.
     */
    public Map<Long, BigDecimal> findMostRecentClosingBalances(List<Long> accountIds) {
        if (accountIds.isEmpty()) {
            return Map.of();
        }
        return dsl.select(STATEMENTS.ACCOUNT_ID, STATEMENTS.CLOSING_BALANCE)
                .distinctOn(STATEMENTS.ACCOUNT_ID)
                .from(STATEMENTS)
                .where(STATEMENTS.ACCOUNT_ID.in(accountIds))
                .orderBy(STATEMENTS.ACCOUNT_ID, STATEMENTS.STATEMENT_DATE.desc())
                .fetchMap(STATEMENTS.ACCOUNT_ID, STATEMENTS.CLOSING_BALANCE);
    }

    public List<Statement> findByAccountId(long accountId) {
        return dsl.selectFrom(STATEMENTS)
                .where(STATEMENTS.ACCOUNT_ID.eq(accountId))
                .orderBy(STATEMENTS.STATEMENT_DATE)
                .fetch()
                .map(StatementRepository::toStatement);
    }

    private static Statement toStatement(io.github.ivarm1984.banksim.jooq.statement.tables.records.StatementsRecord record) {
        return new Statement(
                record.getId(),
                record.getAccountId(),
                record.getStatementDate(),
                record.getOpeningBalance(),
                record.getClosingBalance(),
                record.getCreatedAt());
    }
}
