package io.github.ivarm1984.banksim.treasury;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.ivarm1984.banksim.centralbank.CentralBankService;
import io.github.ivarm1984.banksim.event.DomainEventPublisher;
import io.github.ivarm1984.banksim.ledger.EntryType;
import io.github.ivarm1984.banksim.ledger.JournalEntryRequest;
import io.github.ivarm1984.banksim.ledger.LedgerAccountBalance;
import io.github.ivarm1984.banksim.ledger.LedgerAccountService;
import io.github.ivarm1984.banksim.ledger.LedgerAccountType;
import io.github.ivarm1984.banksim.ledger.LedgerLineRequest;
import io.github.ivarm1984.banksim.ledger.LedgerService;
import io.github.ivarm1984.banksim.policy.PolicyLevers;

/**
 * Simplified, single-number versions of the EU treasury/liquidity ratios -
 * see TODO M6.4's "Complex additions" for the real per-category breakdowns
 * each of these stands in for. Every flat rate below is an illustrative
 * constant (like {@code LoanService.RISK_SPREAD}), not a calibrated CRR
 * figure, and can be tuned later once something in the sim actually needs
 * more realism than a round number.
 */
@Service
public class TreasuryService {

    private static final int RATIO_SCALE = 6;

    /**
     * Share of customer deposits assumed to run off within 30 days, for
     * LCR's outflow denominator. Real CRR (Delegated Reg. 2015/61)
     * differentiates 5-40% by deposit stability/category - not modeled here.
     */
    static final BigDecimal LCR_OUTFLOW_RATE = new BigDecimal("0.10");

    /**
     * NSFR available-stable-funding factor applied to customer deposits.
     * Real CRR2 varies 0-95% by deposit stability - not modeled here.
     */
    static final BigDecimal NSFR_DEPOSIT_ASF_FACTOR = new BigDecimal("0.90");

    /**
     * NSFR required-stable-funding factor applied to the outstanding loan
     * book. Real CRR2 varies by loan type/maturity/risk-weight - not
     * modeled here.
     */
    static final BigDecimal NSFR_LOAN_RSF_FACTOR = new BigDecimal("0.85");

    /** ECB minimum reserve ratio, simplified to all customer deposits (real rule excludes liabilities with maturity over 2 years). */
    static final BigDecimal MIN_RESERVE_REQUIREMENT_RATE = new BigDecimal("0.01");

    /** Risk weight applied to the loan book for the CAR denominator - central bank reserves/cash get 0% (sovereign exposure), loans a flat 100% (unrated standardized-approach exposure). */
    static final BigDecimal LOAN_RISK_WEIGHT = BigDecimal.ONE;

    /** EU minimum total capital ratio (CRR), ignoring the combined buffers real CRR layers on top. */
    static final BigDecimal CAR_MINIMUM = new BigDecimal("0.08");

    private static final BigDecimal DAYS_PER_YEAR = new BigDecimal("365");

    private final LedgerAccountService ledgerAccountService;
    private final TreasuryRatioRepository repository;
    private final LedgerService ledgerService;
    private final CentralBankService centralBankService;
    private final DomainEventPublisher events;
    private final PolicyLevers policyLevers;

    public TreasuryService(
            LedgerAccountService ledgerAccountService, TreasuryRatioRepository repository, LedgerService ledgerService,
            CentralBankService centralBankService, DomainEventPublisher events, PolicyLevers policyLevers) {
        this.ledgerAccountService = ledgerAccountService;
        this.repository = repository;
        this.ledgerService = ledgerService;
        this.centralBankService = centralBankService;
        this.events = events;
        this.policyLevers = policyLevers;
    }

    /** Recomputes every ratio from the current ledger totals and persists one snapshot row for {@code date}. */
    @Transactional
    public TreasuryRatioSnapshot computeAndPersist(LocalDate date) {
        Balances b = currentBalances();

        BigDecimal requiredReserves = b.customerDeposits().multiply(MIN_RESERVE_REQUIREMENT_RATE);
        BigDecimal estimatedThirtyDayOutflow = b.customerDeposits().multiply(LCR_OUTFLOW_RATE);
        BigDecimal hqla = b.bankCash().add(b.centralBankReserves());
        BigDecimal availableStableFunding = b.capitalBase().add(b.customerDeposits().multiply(NSFR_DEPOSIT_ASF_FACTOR));
        BigDecimal requiredStableFunding = b.netLoans().multiply(NSFR_LOAN_RSF_FACTOR);
        BigDecimal riskWeightedAssets = b.netLoans().multiply(LOAN_RISK_WEIGHT);

        return repository.insert(
                date,
                b.bankCash(), b.centralBankReserves(), b.loansReceivable(), b.loanLossProvision(), b.customerDeposits(),
                b.capitalBase(),
                ratio(b.loansReceivable(), b.customerDeposits()),
                ratio(hqla, estimatedThirtyDayOutflow),
                ratio(availableStableFunding, requiredStableFunding),
                requiredReserves,
                ratio(b.centralBankReserves(), requiredReserves),
                ratio(b.capitalBase(), riskWeightedAssets));
    }

    /** The most recently computed snapshot - there is none until at least one simulated day's batch has run. */
    public TreasuryRatioSnapshot latest() {
        return repository.findMostRecent()
                .orElseThrow(() -> new NoSuchElementException("No treasury ratio snapshot yet - run at least one simulated day"));
    }

    /**
     * True while the latest snapshot shows a capital/funding-structure
     * breach (NSFR or CAR below its EU minimum) - {@code LoanService}
     * consults this before originating a new loan. A liquidity breach
     * (LCR/reserve-coverage) does *not* throttle lending - that's handled by
     * {@link #applyFeedback} auto-borrowing reserves instead, since it's a
     * problem borrowing central bank reserves actually fixes. False before
     * any snapshot has ever been computed.
     */
    public boolean isLoanOriginationThrottled() {
        return repository.findMostRecent().map(this::isCapitalBreach).orElse(false);
    }

    /**
     * Reacts to one day's snapshot: a liquidity breach (LCR or reserve
     * coverage below 100%) draws exactly enough from the central bank's
     * marginal lending facility to cover the worse of the two shortfalls
     * (Debit CENTRAL_BANK_RESERVES, Credit CENTRAL_BANK_BORROWINGS); absent a
     * breach, any outstanding facility balance is repaid using only the
     * headroom that keeps both ratios at or above 100% afterward (Debit
     * CENTRAL_BANK_BORROWINGS, Credit CENTRAL_BANK_RESERVES). Always
     * publishes {@link TreasuryRatiosUpdatedEvent}, even when no action was
     * taken. The snapshot itself is left untouched - it's this day's
     * "as-detected" reading; the correction shows up in tomorrow's snapshot,
     * same as a real treasury desk reacting overnight to an EOD report.
     */
    @Transactional
    public void applyFeedback(TreasuryRatioSnapshot snapshot) {
        BigDecimal hqla = snapshot.bankCash().add(snapshot.centralBankReserves());
        BigDecimal estimatedThirtyDayOutflow = snapshot.customerDeposits().multiply(LCR_OUTFLOW_RATE);
        BigDecimal hqlaHeadroom = hqla.subtract(estimatedThirtyDayOutflow);
        BigDecimal reserveHeadroom = snapshot.centralBankReserves().subtract(snapshot.requiredReserves());
        boolean liquidityBreach = hqlaHeadroom.signum() < 0 || reserveHeadroom.signum() < 0;

        BigDecimal amountBorrowed = BigDecimal.ZERO;
        BigDecimal amountRepaid = BigDecimal.ZERO;
        if (liquidityBreach) {
            if (policyLevers.state().autoTapBorrowingFacility()) {
                amountBorrowed = hqlaHeadroom.negate().max(reserveHeadroom.negate()).max(BigDecimal.ZERO)
                        .setScale(2, RoundingMode.HALF_UP);
                if (amountBorrowed.signum() > 0) {
                    postCentralBankFacilityMovement("draw", amountBorrowed, EntryType.DEBIT, snapshot.snapshotDate());
                }
            }
        } else {
            BigDecimal outstanding = ledgerAccountService.singletonCreditBalance(LedgerAccountType.CENTRAL_BANK_BORROWINGS);
            amountRepaid = outstanding.min(hqlaHeadroom).min(reserveHeadroom).max(BigDecimal.ZERO)
                    .setScale(2, RoundingMode.HALF_UP);
            if (amountRepaid.signum() > 0) {
                postCentralBankFacilityMovement("repayment", amountRepaid, EntryType.CREDIT, snapshot.snapshotDate());
            }
        }

        events.publish(new TreasuryRatiosUpdatedEvent(
                snapshot.snapshotDate(), isCapitalBreach(snapshot), liquidityBreach, amountBorrowed, amountRepaid));
    }

    /**
     * Accrues one day's interest on the outstanding central bank facility
     * balance (day-count, at the marginal lending rate - same convention
     * {@code InterestAccrualService} uses for customer accounts), posted as
     * Debit INTEREST_EXPENSE / Credit CENTRAL_BANK_BORROWINGS (capitalized
     * into the balance, same as customer interest accrual). No-op while the
     * facility balance is zero.
     */
    @Transactional
    public void accrueBorrowingInterest(LocalDate date) {
        BigDecimal outstanding = ledgerAccountService.singletonCreditBalance(LedgerAccountType.CENTRAL_BANK_BORROWINGS);
        if (outstanding.signum() <= 0) {
            return;
        }

        BigDecimal dailyRate = centralBankService.currentRates().marginalLendingRate()
                .divide(DAYS_PER_YEAR, 10, RoundingMode.HALF_UP);
        BigDecimal interest = outstanding.multiply(dailyRate).setScale(2, RoundingMode.HALF_UP);
        if (interest.signum() <= 0) {
            return;
        }

        long interestExpenseId = ledgerAccountService.getSingleton(LedgerAccountType.INTEREST_EXPENSE).id();
        long borrowingsId = ledgerAccountService.getSingleton(LedgerAccountType.CENTRAL_BANK_BORROWINGS).id();
        ledgerService.post(new JournalEntryRequest(
                "Central bank facility interest for " + date,
                List.of(
                        new LedgerLineRequest(interestExpenseId, EntryType.DEBIT, interest),
                        new LedgerLineRequest(borrowingsId, EntryType.CREDIT, interest))));
    }

    private void postCentralBankFacilityMovement(String description, BigDecimal amount, EntryType reservesSide, LocalDate date) {
        long reservesId = ledgerAccountService.getSingleton(LedgerAccountType.CENTRAL_BANK_RESERVES).id();
        long borrowingsId = ledgerAccountService.getSingleton(LedgerAccountType.CENTRAL_BANK_BORROWINGS).id();
        EntryType borrowingsSide = reservesSide == EntryType.DEBIT ? EntryType.CREDIT : EntryType.DEBIT;
        ledgerService.post(new JournalEntryRequest(
                "Central bank facility " + description + " on " + date,
                List.of(
                        new LedgerLineRequest(reservesId, reservesSide, amount),
                        new LedgerLineRequest(borrowingsId, borrowingsSide, amount))));
    }

    /**
     * NSFR/CAR breach against their EU minimums, each shifted up by the CEO's
     * {@code targetCapitalBuffer} lever - 0 (the default) reproduces the
     * unshifted regulatory minimums; a positive buffer throttles earlier,
     * i.e. more conservative lending.
     */
    private boolean isCapitalBreach(TreasuryRatioSnapshot snapshot) {
        BigDecimal buffer = policyLevers.state().targetCapitalBuffer();
        BigDecimal nsfrThreshold = BigDecimal.ONE.add(buffer);
        BigDecimal carThreshold = CAR_MINIMUM.add(buffer);
        boolean nsfrBreach = snapshot.netStableFundingRatio() != null && snapshot.netStableFundingRatio().compareTo(nsfrThreshold) < 0;
        boolean carBreach = snapshot.capitalAdequacyRatio() != null && snapshot.capitalAdequacyRatio().compareTo(carThreshold) < 0;
        return nsfrBreach || carBreach;
    }

    private Balances currentBalances() {
        List<LedgerAccountBalance> balances = ledgerAccountService.findAllWithBalances();

        BigDecimal bankCash = debitTotal(balances, LedgerAccountType.BANK_CASH);
        BigDecimal centralBankReserves = debitTotal(balances, LedgerAccountType.CENTRAL_BANK_RESERVES);
        BigDecimal loansReceivable = debitTotal(balances, LedgerAccountType.LOAN_RECEIVABLE);
        BigDecimal customerDeposits = creditTotal(balances, LedgerAccountType.CUSTOMER_LIABILITY);
        BigDecimal bankCapital = creditTotal(balances, LedgerAccountType.BANK_CAPITAL);
        BigDecimal interestIncome = creditTotal(balances, LedgerAccountType.INTEREST_INCOME);
        BigDecimal feeIncome = creditTotal(balances, LedgerAccountType.FEE_INCOME);
        BigDecimal interestExpense = debitTotal(balances, LedgerAccountType.INTEREST_EXPENSE);
        BigDecimal provisionExpense = debitTotal(balances, LedgerAccountType.PROVISION_EXPENSE);
        BigDecimal loanLossProvision = creditTotal(balances, LedgerAccountType.LOAN_LOSS_PROVISION);

        // No retained-earnings sweep exists in this system - income/expense ledger
        // accounts stay permanent rather than closing into BANK_CAPITAL - so net
        // income to date (after provisioning expense, net of releases) is added in
        // directly as the capital-adequacy proxy.
        BigDecimal capitalBase = bankCapital.add(interestIncome).add(feeIncome)
                .subtract(interestExpense).subtract(provisionExpense);

        return new Balances(bankCash, centralBankReserves, loansReceivable, loanLossProvision, customerDeposits, capitalBase);
    }

    private static BigDecimal debitTotal(List<LedgerAccountBalance> balances, LedgerAccountType type) {
        return balances.stream()
                .filter(b -> b.type() == type)
                .map(b -> b.totalDebits().subtract(b.totalCredits()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal creditTotal(List<LedgerAccountBalance> balances, LedgerAccountType type) {
        return balances.stream()
                .filter(b -> b.type() == type)
                .map(b -> b.totalCredits().subtract(b.totalDebits()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Null ("not applicable") rather than a misleading zero/exception when the denominator is zero - e.g. NSFR/CAR before any loan has been originated. */
    private static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        if (denominator.signum() == 0) {
            return null;
        }
        return numerator.divide(denominator, RATIO_SCALE, RoundingMode.HALF_UP);
    }

    private record Balances(
            BigDecimal bankCash, BigDecimal centralBankReserves, BigDecimal loansReceivable, BigDecimal loanLossProvision,
            BigDecimal customerDeposits, BigDecimal capitalBase) {

        /** Loan book at its net carrying amount - gross receivables less the loan-loss provision contra-asset. */
        BigDecimal netLoans() {
            return loansReceivable.subtract(loanLossProvision);
        }
    }
}
