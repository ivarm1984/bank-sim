package io.github.ivarm1984.banksim.treasury;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.ivarm1984.banksim.ledger.LedgerAccountBalance;
import io.github.ivarm1984.banksim.ledger.LedgerAccountService;
import io.github.ivarm1984.banksim.ledger.LedgerAccountType;

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

    private final LedgerAccountService ledgerAccountService;
    private final TreasuryRatioRepository repository;

    public TreasuryService(LedgerAccountService ledgerAccountService, TreasuryRatioRepository repository) {
        this.ledgerAccountService = ledgerAccountService;
        this.repository = repository;
    }

    /** Recomputes every ratio from the current ledger totals and persists one snapshot row for {@code date}. */
    @Transactional
    public TreasuryRatioSnapshot computeAndPersist(LocalDate date) {
        Balances b = currentBalances();

        BigDecimal requiredReserves = b.customerDeposits().multiply(MIN_RESERVE_REQUIREMENT_RATE);
        BigDecimal estimatedThirtyDayOutflow = b.customerDeposits().multiply(LCR_OUTFLOW_RATE);
        BigDecimal hqla = b.bankCash().add(b.centralBankReserves());
        BigDecimal availableStableFunding = b.capitalBase().add(b.customerDeposits().multiply(NSFR_DEPOSIT_ASF_FACTOR));
        BigDecimal requiredStableFunding = b.loansReceivable().multiply(NSFR_LOAN_RSF_FACTOR);
        BigDecimal riskWeightedAssets = b.loansReceivable().multiply(LOAN_RISK_WEIGHT);

        return repository.insert(
                date,
                b.bankCash(), b.centralBankReserves(), b.loansReceivable(), b.customerDeposits(), b.capitalBase(),
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

        // No retained-earnings sweep exists in this system - income/expense ledger
        // accounts stay permanent rather than closing into BANK_CAPITAL - so net
        // income to date is added in directly as the capital-adequacy proxy.
        BigDecimal capitalBase = bankCapital.add(interestIncome).add(feeIncome).subtract(interestExpense);

        return new Balances(bankCash, centralBankReserves, loansReceivable, customerDeposits, capitalBase);
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
            BigDecimal bankCash, BigDecimal centralBankReserves, BigDecimal loansReceivable,
            BigDecimal customerDeposits, BigDecimal capitalBase) {
    }
}
