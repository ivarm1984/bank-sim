package io.github.ivarm1984.banksim.interest;

import static io.github.ivarm1984.banksim.jooq.interest.tables.InterestAccruals.INTEREST_ACCRUALS;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
public class InterestAccrualRepository {

    private final DSLContext dsl;

    public InterestAccrualRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public InterestAccrual insert(
            long accountId, LocalDate accrualDate, BigDecimal principalBalance, BigDecimal annualRate,
            BigDecimal amount, Long journalEntryId) {
        var record = dsl.insertInto(INTEREST_ACCRUALS)
                .set(INTEREST_ACCRUALS.ACCOUNT_ID, accountId)
                .set(INTEREST_ACCRUALS.ACCRUAL_DATE, accrualDate)
                .set(INTEREST_ACCRUALS.PRINCIPAL_BALANCE, principalBalance)
                .set(INTEREST_ACCRUALS.ANNUAL_RATE, annualRate)
                .set(INTEREST_ACCRUALS.AMOUNT, amount)
                .set(INTEREST_ACCRUALS.JOURNAL_ENTRY_ID, journalEntryId)
                .returning()
                .fetchOne();
        return toInterestAccrual(record);
    }

    public List<InterestAccrual> findByAccountId(long accountId) {
        return dsl.selectFrom(INTEREST_ACCRUALS)
                .where(INTEREST_ACCRUALS.ACCOUNT_ID.eq(accountId))
                .orderBy(INTEREST_ACCRUALS.ACCRUAL_DATE)
                .fetch()
                .map(InterestAccrualRepository::toInterestAccrual);
    }

    private static InterestAccrual toInterestAccrual(
            io.github.ivarm1984.banksim.jooq.interest.tables.records.InterestAccrualsRecord record) {
        return new InterestAccrual(
                record.getId(),
                record.getAccountId(),
                record.getAccrualDate(),
                record.getPrincipalBalance(),
                record.getAnnualRate(),
                record.getAmount(),
                record.getJournalEntryId(),
                record.getCreatedAt());
    }
}
