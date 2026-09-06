package io.github.ivarm1984.banksim.interest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.ivarm1984.banksim.account.Account;
import io.github.ivarm1984.banksim.account.AccountService;
import io.github.ivarm1984.banksim.ledger.EntryType;
import io.github.ivarm1984.banksim.ledger.JournalEntryRequest;
import io.github.ivarm1984.banksim.ledger.LedgerAccountService;
import io.github.ivarm1984.banksim.ledger.LedgerAccountType;
import io.github.ivarm1984.banksim.ledger.LedgerLineRequest;
import io.github.ivarm1984.banksim.ledger.LedgerService;

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

    public InterestAccrualService(
            AccountService accountService, LedgerAccountService ledgerAccountService, LedgerService ledgerService,
            InterestRatePolicyRepository ratePolicyRepository, InterestAccrualRepository interestAccrualRepository) {
        this.accountService = accountService;
        this.ledgerAccountService = ledgerAccountService;
        this.ledgerService = ledgerService;
        this.ratePolicyRepository = ratePolicyRepository;
        this.interestAccrualRepository = interestAccrualRepository;
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
        BigDecimal annualRate = ratePolicyRepository.findAnnualRate(account.accountType());
        BigDecimal amount = account.currentBalance()
                .multiply(annualRate)
                .divide(DAYS_PER_YEAR, 10, RoundingMode.HALF_UP)
                .setScale(2, RoundingMode.HALF_UP);

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

    public List<InterestAccrual> findByAccountId(long accountId) {
        return interestAccrualRepository.findByAccountId(accountId);
    }
}
