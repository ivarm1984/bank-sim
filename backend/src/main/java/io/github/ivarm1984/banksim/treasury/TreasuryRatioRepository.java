package io.github.ivarm1984.banksim.treasury;

import static io.github.ivarm1984.banksim.jooq.treasury.tables.RatioSnapshots.RATIO_SNAPSHOTS;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
public class TreasuryRatioRepository {

    private final DSLContext dsl;

    public TreasuryRatioRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public TreasuryRatioSnapshot insert(
            LocalDate snapshotDate, BigDecimal bankCash, BigDecimal centralBankReserves, BigDecimal loansReceivable,
            BigDecimal loanLossProvision, BigDecimal customerDeposits, BigDecimal capitalBase, BigDecimal loanToDepositRatio,
            BigDecimal liquidityCoverageRatio, BigDecimal netStableFundingRatio, BigDecimal requiredReserves,
            BigDecimal reserveCoverageRatio, BigDecimal capitalAdequacyRatio) {
        var record = dsl.insertInto(RATIO_SNAPSHOTS)
                .set(RATIO_SNAPSHOTS.SNAPSHOT_DATE, snapshotDate)
                .set(RATIO_SNAPSHOTS.BANK_CASH, bankCash)
                .set(RATIO_SNAPSHOTS.CENTRAL_BANK_RESERVES, centralBankReserves)
                .set(RATIO_SNAPSHOTS.LOANS_RECEIVABLE, loansReceivable)
                .set(RATIO_SNAPSHOTS.LOAN_LOSS_PROVISION, loanLossProvision)
                .set(RATIO_SNAPSHOTS.CUSTOMER_DEPOSITS, customerDeposits)
                .set(RATIO_SNAPSHOTS.CAPITAL_BASE, capitalBase)
                .set(RATIO_SNAPSHOTS.LOAN_TO_DEPOSIT_RATIO, loanToDepositRatio)
                .set(RATIO_SNAPSHOTS.LIQUIDITY_COVERAGE_RATIO, liquidityCoverageRatio)
                .set(RATIO_SNAPSHOTS.NET_STABLE_FUNDING_RATIO, netStableFundingRatio)
                .set(RATIO_SNAPSHOTS.REQUIRED_RESERVES, requiredReserves)
                .set(RATIO_SNAPSHOTS.RESERVE_COVERAGE_RATIO, reserveCoverageRatio)
                .set(RATIO_SNAPSHOTS.CAPITAL_ADEQUACY_RATIO, capitalAdequacyRatio)
                .returning()
                .fetchOne();
        return toSnapshot(record);
    }

    /** Most recently dated snapshot - the source of the "current" ratios shown to REST callers. */
    public Optional<TreasuryRatioSnapshot> findMostRecent() {
        var record = dsl.selectFrom(RATIO_SNAPSHOTS)
                .orderBy(RATIO_SNAPSHOTS.SNAPSHOT_DATE.desc())
                .limit(1)
                .fetchOne();
        return Optional.ofNullable(record).map(TreasuryRatioRepository::toSnapshot);
    }

    private static TreasuryRatioSnapshot toSnapshot(
            io.github.ivarm1984.banksim.jooq.treasury.tables.records.RatioSnapshotsRecord record) {
        return new TreasuryRatioSnapshot(
                record.getId(),
                record.getSnapshotDate(),
                record.getBankCash(),
                record.getCentralBankReserves(),
                record.getLoansReceivable(),
                record.getLoanLossProvision(),
                record.getCustomerDeposits(),
                record.getCapitalBase(),
                record.getLoanToDepositRatio(),
                record.getLiquidityCoverageRatio(),
                record.getNetStableFundingRatio(),
                record.getRequiredReserves(),
                record.getReserveCoverageRatio(),
                record.getCapitalAdequacyRatio(),
                record.getCreatedAt());
    }
}
