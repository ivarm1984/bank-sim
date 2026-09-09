package io.github.ivarm1984.banksim.eventinjector;

import static io.github.ivarm1984.banksim.jooq.eventinjector.tables.RateShocks.RATE_SHOCKS;
import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.sum;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
public class RateShockRepository {

    private final DSLContext dsl;

    public RateShockRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public RateShock insert(LocalDate shockDate, BigDecimal delta) {
        var record = dsl.insertInto(RATE_SHOCKS)
                .set(RATE_SHOCKS.SHOCK_DATE, shockDate)
                .set(RATE_SHOCKS.DELTA, delta)
                .returning()
                .fetchOne();
        return toRateShock(record);
    }

    /** Sum of every shock delta dated on or before {@code asOf} - the effective offset as of that date. */
    public BigDecimal sumDeltaOnOrBefore(LocalDate asOf) {
        var totalDelta = coalesce(sum(RATE_SHOCKS.DELTA), BigDecimal.ZERO);
        return dsl.select(totalDelta)
                .from(RATE_SHOCKS)
                .where(RATE_SHOCKS.SHOCK_DATE.le(asOf))
                .fetchOne(totalDelta);
    }

    public List<RateShock> findAll() {
        return dsl.selectFrom(RATE_SHOCKS)
                .orderBy(RATE_SHOCKS.SHOCK_DATE)
                .fetch()
                .map(RateShockRepository::toRateShock);
    }

    private static RateShock toRateShock(io.github.ivarm1984.banksim.jooq.eventinjector.tables.records.RateShocksRecord record) {
        return new RateShock(record.getId(), record.getShockDate(), record.getDelta(), record.getCreatedAt());
    }
}
