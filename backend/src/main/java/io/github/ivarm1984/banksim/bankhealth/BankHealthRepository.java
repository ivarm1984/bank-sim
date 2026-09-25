package io.github.ivarm1984.banksim.bankhealth;

import static io.github.ivarm1984.banksim.jooq.bankhealth.tables.HealthSnapshots.HEALTH_SNAPSHOTS;

import java.time.LocalDate;
import java.util.Optional;

import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
public class BankHealthRepository {

    private final DSLContext dsl;

    public BankHealthRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public BankHealthSnapshot insert(
            LocalDate snapshotDate, BankHealthStatus status, BreachStreaks streaks) {
        var record = dsl.insertInto(HEALTH_SNAPSHOTS)
                .set(HEALTH_SNAPSHOTS.SNAPSHOT_DATE, snapshotDate)
                .set(HEALTH_SNAPSHOTS.STATUS, status.name())
                .set(HEALTH_SNAPSHOTS.CAPITAL_BREACH_STREAK, streaks.capital())
                .set(HEALTH_SNAPSHOTS.CAPITAL_SHORTFALL_STREAK, streaks.capitalShortfall())
                .set(HEALTH_SNAPSHOTS.FUNDING_BREACH_STREAK, streaks.funding())
                .set(HEALTH_SNAPSHOTS.LIQUIDITY_BREACH_STREAK, streaks.liquidity())
                .returning()
                .fetchOne();
        return toSnapshot(record);
    }

    /** Most recently dated snapshot - the source of the "current" game state shown to REST callers. */
    public Optional<BankHealthSnapshot> findMostRecent() {
        var record = dsl.selectFrom(HEALTH_SNAPSHOTS)
                .orderBy(HEALTH_SNAPSHOTS.SNAPSHOT_DATE.desc())
                .limit(1)
                .fetchOne();
        return Optional.ofNullable(record).map(BankHealthRepository::toSnapshot);
    }

    private static BankHealthSnapshot toSnapshot(
            io.github.ivarm1984.banksim.jooq.bankhealth.tables.records.HealthSnapshotsRecord record) {
        return new BankHealthSnapshot(
                record.getId(),
                record.getSnapshotDate(),
                BankHealthStatus.valueOf(record.getStatus()),
                record.getCapitalBreachStreak(),
                record.getCapitalShortfallStreak(),
                record.getFundingBreachStreak(),
                record.getLiquidityBreachStreak(),
                record.getCreatedAt());
    }
}
