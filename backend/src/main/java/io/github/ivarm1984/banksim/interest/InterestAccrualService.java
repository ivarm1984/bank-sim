package io.github.ivarm1984.banksim.interest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.ivarm1984.banksim.account.Account;
import io.github.ivarm1984.banksim.account.AccountService;
import io.github.ivarm1984.banksim.account.AccountType;
import io.github.ivarm1984.banksim.centralbank.CentralBankService;
import io.github.ivarm1984.banksim.ledger.EntryType;
import io.github.ivarm1984.banksim.ledger.JournalEntryRequest;
import io.github.ivarm1984.banksim.ledger.LedgerAccountService;
import io.github.ivarm1984.banksim.ledger.LedgerAccountType;
import io.github.ivarm1984.banksim.ledger.LedgerLineRequest;
import io.github.ivarm1984.banksim.ledger.LedgerService;
import io.github.ivarm1984.banksim.policy.PolicyLevers;

/**
 * Computes and posts one day's simple interest for one account. Kept as a
 * separate bean from {@link InterestAccrualScheduler} (rather than one
 * self-invoked method) so {@link #accrueForAccount} runs through Spring's
 * transactional proxy - one transaction per account, not one giant
 * transaction for the whole day's batch.
 */
@Service
public class InterestAccrualService {

    private static final BigDecimal DAYS_PER_YEAR = new BigDecimal("365");

    private final AccountService accountService;
    private final LedgerAccountService ledgerAccountService;
    private final LedgerService ledgerService;
    private final InterestRatePolicyRepository ratePolicyRepository;
    private final InterestAccrualRepository interestAccrualRepository;
    private final CentralBankService centralBankService;
    private final PolicyLevers policyLevers;

    public InterestAccrualService(
            AccountService accountService, LedgerAccountService ledgerAccountService, LedgerService ledgerService,
            InterestRatePolicyRepository ratePolicyRepository, InterestAccrualRepository interestAccrualRepository,
            CentralBankService centralBankService, PolicyLevers policyLevers) {
        this.accountService = accountService;
        this.ledgerAccountService = ledgerAccountService;
        this.ledgerService = ledgerService;
        this.ratePolicyRepository = ratePolicyRepository;
        this.interestAccrualRepository = interestAccrualRepository;
        this.centralBankService = centralBankService;
        this.policyLevers = policyLevers;
    }

    /**
     * Accrues one day's interest on {@code accountId}'s current balance at
     * its account type's seeded annual rate, posts it to the ledger (Debit
     * INTEREST_EXPENSE, Credit the account's customer liability) when the
     * amount is positive, and records the accrual either way (unique per
     * account+date).
     */
    @Transactional
    public InterestAccrual accrueForAccount(long accountId, LocalDate date) {
        Account account = accountService.findById(accountId);
        BigDecimal annualRate = annualRateFor(account.accountType());
        BigDecimal amount = accrualAmount(account.currentBalance(), annualRate);

        Long journalEntryId = null;
        if (amount.signum() > 0) {
            long interestExpenseId = ledgerAccountService.getSingleton(LedgerAccountType.INTEREST_EXPENSE).id();
            long liabilityId = ledgerAccountService.findCustomerLiabilityAccount(accountId).id();
            journalEntryId = ledgerService.post(new JournalEntryRequest(
                    "Interest accrual for account " + accountId + " on " + date,
                    List.of(
                            new LedgerLineRequest(interestExpenseId, EntryType.DEBIT, amount),
                            new LedgerLineRequest(liabilityId, EntryType.CREDIT, amount))));
        }

        return interestAccrualRepository.insert(accountId, date, account.currentBalance(), annualRate, amount, journalEntryId);
    }

    /**
     * Bulk version of {@link #accrueForAccount(long, LocalDate)} for a chunk of accounts
     * (see {@code InterestAccrualScheduler}): computes every account's amount off the
     * balance already loaded on {@code accounts} (no per-account re-read), posts at most
     * one shared journal entry for the whole chunk via
     * {@link LedgerService#postBulkAccountCredits}, and writes every accrual row (posted
     * or zero) in one multi-row INSERT.
     */
    @Transactional
    public List<InterestAccrual> accrueForChunk(List<Account> accounts, Map<AccountType, BigDecimal> ratesByType, LocalDate date) {
        record Computed(long accountId, BigDecimal principalBalance, BigDecimal annualRate, BigDecimal amount) {}

        List<Computed> computed = accounts.stream()
                .map(account -> {
                    BigDecimal annualRate = ratesByType.get(account.accountType());
                    return new Computed(account.id(), account.currentBalance(), annualRate, accrualAmount(account.currentBalance(), annualRate));
                })
                .toList();

        List<Computed> accruing = computed.stream().filter(c -> c.amount().signum() > 0).toList();
        Map<Long, Long> journalEntryIdByAccount = new HashMap<>();
        if (!accruing.isEmpty()) {
            Map<Long, Long> liabilityLedgerAccountIds = ledgerAccountService.findCustomerLiabilityAccountIdsByAccountIds(
                    accruing.stream().map(Computed::accountId).toList());
            long interestExpenseId = ledgerAccountService.getSingleton(LedgerAccountType.INTEREST_EXPENSE).id();
            List<LedgerService.AccountCredit> credits = accruing.stream()
                    .map(c -> new LedgerService.AccountCredit(c.accountId(), liabilityLedgerAccountIds.get(c.accountId()), c.amount()))
                    .toList();
            long journalEntryId = ledgerService.postBulkAccountCredits(
                    "Interest accrual batch for " + date, interestExpenseId, credits);
            accruing.forEach(c -> journalEntryIdByAccount.put(c.accountId(), journalEntryId));
        }

        List<InterestAccrualRepository.NewAccrual> toInsert = computed.stream()
                .map(c -> new InterestAccrualRepository.NewAccrual(
                        c.accountId(), date, c.principalBalance(), c.annualRate(), c.amount(),
                        journalEntryIdByAccount.get(c.accountId())))
                .toList();
        return interestAccrualRepository.insertBatch(toInsert);
    }

    private static BigDecimal accrualAmount(BigDecimal balance, BigDecimal annualRate) {
        return balance
                .multiply(annualRate)
                .divide(DAYS_PER_YEAR, 10, RoundingMode.HALF_UP)
                .setScale(2, RoundingMode.HALF_UP);
    }

    public List<InterestAccrual> findByAccountId(long accountId) {
        return interestAccrualRepository.findByAccountId(accountId);
    }

    /**
     * Every account-type rate, for {@link #accrueForChunk} - fetched once per
     * batch, not once per chunk. SAVINGS is overridden with the same
     * central-bank-derived value {@link #annualRateFor} computes; the other
     * types are the flat seeded DB rate.
     */
    public Map<AccountType, BigDecimal> currentRates() {
        Map<AccountType, BigDecimal> rates = new HashMap<>(ratePolicyRepository.findAllRates());
        rates.put(AccountType.SAVINGS, savingsRate());
        return rates;
    }

    /**
     * SAVINGS prices off the central bank policy rate plus the CEO's
     * lever-adjustable spread (see {@code policy.PolicyLevers}) - the
     * rewiring deferred in M6.2. CHECKING/TERM_DEPOSIT stay on the flat
     * seeded {@code interest.rate_policies} rate.
     */
    private BigDecimal annualRateFor(AccountType type) {
        return type == AccountType.SAVINGS ? savingsRate() : ratePolicyRepository.findAnnualRate(type);
    }

    /**
     * Floored at zero: a spread below the policy rate (e.g. policy 0% +
     * spread -5%) would otherwise produce a negative rate that the daily
     * accrual silently skips while the rate itself is still reported
     * negative. Retail deposits in the euro area were effectively never
     * charged negative rates even during the ECB's negative-rate era, so 0%
     * is the rate actually applied and reported.
     */
    private BigDecimal savingsRate() {
        return centralBankService.currentRates().policyRate().add(policyLevers.state().savingsRateSpread()).max(BigDecimal.ZERO);
    }
}
