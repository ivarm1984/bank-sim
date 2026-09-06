package io.github.ivarm1984.banksim.statement;

import static io.github.ivarm1984.banksim.jooq.statement.tables.Statements.STATEMENTS;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

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
