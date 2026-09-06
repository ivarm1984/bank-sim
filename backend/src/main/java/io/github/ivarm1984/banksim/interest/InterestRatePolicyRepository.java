package io.github.ivarm1984.banksim.interest;

import static io.github.ivarm1984.banksim.jooq.interest.tables.RatePolicies.RATE_POLICIES;

import java.math.BigDecimal;
import java.util.NoSuchElementException;

import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import io.github.ivarm1984.banksim.account.AccountType;

@Repository
public class InterestRatePolicyRepository {

    private final DSLContext dsl;

    public InterestRatePolicyRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** Looks up the seeded annual interest rate for an account type. */
    public BigDecimal findAnnualRate(AccountType accountType) {
        var record = dsl.selectFrom(RATE_POLICIES)
                .where(RATE_POLICIES.ACCOUNT_TYPE.eq(accountType.name()))
                .fetchOne();
        if (record == null) {
            throw new NoSuchElementException("No rate policy for account type " + accountType);
        }
        return record.getAnnualRate();
    }
}
