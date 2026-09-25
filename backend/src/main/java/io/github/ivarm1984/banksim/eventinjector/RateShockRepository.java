package io.github.ivarm1984.banksim.eventinjector;

import static io.github.ivarm1984.banksim.jooq.eventinjector.tables.RateShocks.RATE_SHOCKS;
import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.sum;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/**
 * Keeps a one-entry cache of the last {@link #offsetAsOf} read -
 * {@code CentralBankService.currentRates()} is called on every loan
 * origination and interest batch, but the answer only changes when the
 * simulated date moves or a shock is inserted. {@link #insert} invalidates
 * it, so every write path (including tests inserting rows directly) stays
 * consistent.
 */
@Repository
public class RateShockRepository {

    private final DSLContext dsl;
    private volatile CachedOffset cachedOffset;

    public RateShockRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public RateShock insert(LocalDate shockDate, BigDecimal delta) {
        cachedOffset = null;
        var record = dsl.insertInto(RATE_SHOCKS)
                .set(RATE_SHOCKS.SHOCK_DATE, shockDate)
                .set(RATE_SHOCKS.DELTA, delta)
                .returning()
                .fetchOne();
        return toRateShock(record);
    }

    /** Cached {@link #sumDeltaOnOrBefore} - see the class docs. */
    public BigDecimal offsetAsOf(LocalDate asOf) {
        CachedOffset cached = cachedOffset;
        if (cached != null && cached.asOf().equals(asOf)) {
            return cached.offset();
        }
        BigDecimal offset = sumDeltaOnOrBefore(asOf);
        cachedOffset = new CachedOffset(asOf, offset);
        return offset;
    }

    /** Sum of every shock delta dated on or before {@code asOf} - the effective offset as of that date. Always hits the DB. */
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

    private record CachedOffset(LocalDate asOf, BigDecimal offset) {
    }

    private static RateShock toRateShock(io.github.ivarm1984.banksim.jooq.eventinjector.tables.records.RateShocksRecord record) {
        return new RateShock(record.getId(), record.getShockDate(), record.getDelta(), record.getCreatedAt());
    }
}
