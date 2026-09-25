package io.github.ivarm1984.banksim.treasury;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.NoSuchElementException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.ivarm1984.banksim.centralbank.CentralBankService;
import io.github.ivarm1984.banksim.clock.SimulationClock;
import io.github.ivarm1984.banksim.event.DomainEventPublisher;
import io.github.ivarm1984.banksim.ledger.EntryType;
import io.github.ivarm1984.banksim.ledger.JournalEntryRequest;
import io.github.ivarm1984.banksim.ledger.LedgerAccountBalance;
import io.github.ivarm1984.banksim.ledger.LedgerAccountService;
import io.github.ivarm1984.banksim.ledger.LedgerAccountType;
import io.github.ivarm1984.banksim.ledger.LedgerLineRequest;
import io.github.ivarm1984.banksim.ledger.LedgerService;
import io.github.ivarm1984.banksim.loan.CreditExposure;
import io.github.ivarm1984.banksim.loan.LoanExposureService;
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

    /*
     * Standardised-approach credit risk weights for the CAR denominator,
     * applied to exposures net of their loss allowance (CRR Art. 111).
     * Central bank reserves/cash get 0% (sovereign exposure) and aren't listed.
     */
    /** CRR Art. 125 - exposures fully secured by residential property. */
    static final BigDecimal MORTGAGE_RISK_WEIGHT = new BigDecimal("0.35");
    /** CRR Art. 123 - regulatory retail exposures. */
    static final BigDecimal RETAIL_RISK_WEIGHT = new BigDecimal("0.75");
    /** CRR Art. 122 - unrated corporate exposures. */
    static final BigDecimal CORPORATE_RISK_WEIGHT = BigDecimal.ONE;
    /** CRR Art. 127 - defaulted exposures whose specific credit risk adjustments are below 20% of the exposure... */
    static final BigDecimal DEFAULTED_UNDER_PROVISIONED_RISK_WEIGHT = new BigDecimal("1.50");
    /** ...and at or above 20%. */
    static final BigDecimal DEFAULTED_PROVISIONED_RISK_WEIGHT = BigDecimal.ONE;
    static final BigDecimal DEFAULTED_PROVISION_COVERAGE_THRESHOLD = new BigDecimal("0.20");

    /*
     * The regulatory capital stack, as total capital / RWA. Illustrative
     * figures - a real bank's P2R is set bank-by-bank in its SREP decision,
     * and its combined buffer also includes countercyclical/systemic buffers,
     * not modeled here.
     */
    /** Pillar 1 minimum total capital ratio - CRR Art. 92(1)(c). */
    static final BigDecimal PILLAR_1_MINIMUM = new BigDecimal("0.08");
    /** Pillar 2 requirement - CRD Art. 104(1)(a). */
    static final BigDecimal PILLAR_2_REQUIREMENT = new BigDecimal("0.02");
    /** Capital conservation buffer - CRD Art. 129. */
    static final BigDecimal CAPITAL_CONSERVATION_BUFFER = new BigDecimal("0.025");
    /**
     * Total SREP capital requirement (P1 + P2R). Breaching it means the bank
     * is failing or likely to fail (BRRD Art. 32(4)(a)) - CEO-mode game over.
     */
    public static final BigDecimal TOTAL_SREP_CAPITAL_REQUIREMENT = PILLAR_1_MINIMUM.add(PILLAR_2_REQUIREMENT);
    /**
     * Overall capital requirement (TSCR + combined buffer). Dipping into the
     * buffer isn't a breach of minimum requirements, but triggers maximum
     * distributable amount restrictions (CRD Art. 141) - a regulator warning.
     */
    public static final BigDecimal OVERALL_CAPITAL_REQUIREMENT = TOTAL_SREP_CAPITAL_REQUIREMENT.add(CAPITAL_CONSERVATION_BUFFER);

    /** CRR2 Art. 428b - NSFR must stay at or above 100%. */
    static final BigDecimal NSFR_MINIMUM = BigDecimal.ONE;

    /**
     * Haircut on credit claims (loans) pledged as collateral for the central
     * bank's marginal lending facility. The Eurosystem only lends against
     * eligible collateral valued after haircuts, and never accepts defaulted
     * claims - so the facility is capped, not unlimited. Illustrative figure;
     * real credit-claim haircuts vary by maturity/rating.
     */
    static final BigDecimal CREDIT_CLAIM_HAIRCUT = new BigDecimal("0.30");

    private static final BigDecimal DAYS_PER_YEAR = new BigDecimal("365");

    private final LedgerAccountService ledgerAccountService;
    private final TreasuryRatioRepository repository;
    private final LedgerService ledgerService;
    private final CentralBankService centralBankService;
    private final DomainEventPublisher events;
    private final PolicyLevers policyLevers;
    private final LoanExposureService loanExposureService;

    public TreasuryService(
            LedgerAccountService ledgerAccountService, TreasuryRatioRepository repository, LedgerService ledgerService,
            CentralBankService centralBankService, DomainEventPublisher events, PolicyLevers policyLevers,
            LoanExposureService loanExposureService) {
        this.ledgerAccountService = ledgerAccountService;
        this.repository = repository;
        this.ledgerService = ledgerService;
        this.centralBankService = centralBankService;
        this.events = events;
        this.policyLevers = policyLevers;
        this.loanExposureService = loanExposureService;
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
        List<CreditExposure> exposures = loanExposureService.activeExposures();
        BigDecimal riskWeightedAssets = riskWeightedAssets(exposures);

        return repository.insert(
                date,
                b.bankCash(), b.centralBankReserves(), b.loansReceivable(), b.loanLossProvision(), b.customerDeposits(),
                b.capitalBase(), riskWeightedAssets, returnOnEquity(b, date), nonPerformingLoanRatio(exposures),
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
     * True while the latest snapshot shows CAR below the CEO's management
     * target (OCR + the {@code targetCapitalBuffer} lever) or NSFR below 100%
     * (no stable funding for more long-term lending) - {@code LoanService}
     * consults this before originating a new loan. A liquidity breach
     * (LCR/reserve-coverage) does *not* throttle lending - that's handled by
     * {@link #applyFeedback} auto-borrowing reserves instead, since it's a
     * problem borrowing central bank reserves actually fixes. False before
     * any snapshot has ever been computed.
     */
    public boolean isLoanOriginationThrottled() {
        return repository.findMostRecent().map(this::isLoanOriginationThrottled).orElse(false);
    }

    /**
     * Reacts to one day's snapshot: a liquidity breach (LCR or reserve
     * coverage below 100%) draws from the central bank's marginal lending
     * facility enough to cover the worse of the two shortfalls - but never
     * more than the unused eligible collateral allows (see
     * {@link #CREDIT_CLAIM_HAIRCUT}), so a big enough outflow can outrun the
     * safety net (Debit CENTRAL_BANK_RESERVES, Credit
     * CENTRAL_BANK_BORROWINGS); absent a
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
                BigDecimal shortfall = hqlaHeadroom.negate().max(reserveHeadroom.negate()).max(BigDecimal.ZERO);
                amountBorrowed = shortfall.min(unusedCollateralCapacity()).setScale(2, RoundingMode.HALF_UP);
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
                snapshot.snapshotDate(),
                isLoanOriginationThrottled(snapshot),
                isBelow(snapshot.capitalAdequacyRatio(), OVERALL_CAPITAL_REQUIREMENT),
                isBelow(snapshot.capitalAdequacyRatio(), TOTAL_SREP_CAPITAL_REQUIREMENT),
                isBelow(snapshot.netStableFundingRatio(), NSFR_MINIMUM),
                liquidityBreach,
                amountBorrowed,
                amountRepaid,
                snapshot.returnOnEquity()));
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
     * The management throttle: CAR below OCR plus the CEO's own
     * {@code targetCapitalBuffer} (a management buffer on top of the
     * regulatory stack - 0 means "lend right up to OCR"), or NSFR below its
     * 100% minimum. The buffer shifts only the capital threshold - it's a
     * capital concept, unrelated to NSFR's funding structure - and it only
     * throttles lending: warnings/game over are judged against the
     * regulatory thresholds alone, so a more prudent CEO is never punished.
     */
    private boolean isLoanOriginationThrottled(TreasuryRatioSnapshot snapshot) {
        BigDecimal carTarget = OVERALL_CAPITAL_REQUIREMENT.add(policyLevers.state().targetCapitalBuffer());
        return isBelow(snapshot.capitalAdequacyRatio(), carTarget) || isBelow(snapshot.netStableFundingRatio(), NSFR_MINIMUM);
    }

    /** A null ratio (zero denominator - e.g. no loans yet) is never a breach. */
    private static boolean isBelow(BigDecimal ratio, BigDecimal threshold) {
        return ratio != null && ratio.compareTo(threshold) < 0;
    }

    /** Package-visible for direct unit testing - see the risk-weight constants above. */
    static BigDecimal riskWeightedAssets(List<CreditExposure> exposures) {
        return exposures.stream()
                .map(e -> e.netExposure().multiply(riskWeight(e)))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(4, RoundingMode.HALF_UP);
    }

    static BigDecimal riskWeight(CreditExposure exposure) {
        if (exposure.defaulted()) {
            BigDecimal coverageThreshold = exposure.outstandingPrincipal().multiply(DEFAULTED_PROVISION_COVERAGE_THRESHOLD);
            return exposure.provision().compareTo(coverageThreshold) < 0
                    ? DEFAULTED_UNDER_PROVISIONED_RISK_WEIGHT
                    : DEFAULTED_PROVISIONED_RISK_WEIGHT;
        }
        return switch (exposure.loanType()) {
            case MORTGAGE -> MORTGAGE_RISK_WEIGHT;
            case CONSUMER -> RETAIL_RISK_WEIGHT;
            case BUSINESS -> CORPORATE_RISK_WEIGHT;
        };
    }

    /**
     * Gross defaulted (Stage 3) loans / gross loans - the EBA's NPL ratio
     * (risk indicator AQT_3.2), gross of allowances. Written-off loans have
     * left the book, so write-offs bring it down. Null while there are no
     * loans. Package-visible for direct unit testing.
     */
    static BigDecimal nonPerformingLoanRatio(List<CreditExposure> exposures) {
        BigDecimal gross = exposures.stream()
                .map(CreditExposure::outstandingPrincipal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal nonPerforming = exposures.stream()
                .filter(CreditExposure::defaulted)
                .map(CreditExposure::outstandingPrincipal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return ratio(nonPerforming, gross);
    }

    /** Eligible (non-defaulted) credit claims after haircut, less what's already drawn against them. */
    private BigDecimal unusedCollateralCapacity() {
        BigDecimal collateralValue = loanExposureService.activeExposures().stream()
                .filter(e -> !e.defaulted())
                .map(CreditExposure::outstandingPrincipal)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .multiply(BigDecimal.ONE.subtract(CREDIT_CLAIM_HAIRCUT));
        BigDecimal outstanding = ledgerAccountService.singletonCreditBalance(LedgerAccountType.CENTRAL_BANK_BORROWINGS);
        return collateralValue.subtract(outstanding).max(BigDecimal.ZERO);
    }

    /**
     * Average annual return on equity since the epoch: net income to date
     * (capital base above paid-in capital) / paid-in capital / years elapsed.
     * Null on the epoch day itself.
     */
    private static BigDecimal returnOnEquity(Balances b, LocalDate date) {
        long days = ChronoUnit.DAYS.between(SimulationClock.epoch(), date);
        if (days <= 0 || b.bankCapital().signum() <= 0) {
            return null;
        }
        BigDecimal years = BigDecimal.valueOf(days).divide(DAYS_PER_YEAR, 10, RoundingMode.HALF_UP);
        return b.capitalBase().subtract(b.bankCapital())
                .divide(b.bankCapital(), 10, RoundingMode.HALF_UP)
                .divide(years, RATIO_SCALE, RoundingMode.HALF_UP);
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

        return new Balances(bankCash, centralBankReserves, loansReceivable, loanLossProvision, customerDeposits, bankCapital, capitalBase);
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
            BigDecimal customerDeposits, BigDecimal bankCapital, BigDecimal capitalBase) {

        /** Loan book at its net carrying amount - gross receivables less the loan-loss provision contra-asset. */
        BigDecimal netLoans() {
            return loansReceivable.subtract(loanLossProvision);
        }
    }
}
