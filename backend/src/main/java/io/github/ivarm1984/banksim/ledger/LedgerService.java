package io.github.ivarm1984.banksim.ledger;

import static io.github.ivarm1984.banksim.jooq.ledger.tables.JournalEntries.JOURNAL_ENTRIES;
import static io.github.ivarm1984.banksim.jooq.ledger.tables.LedgerLines.LEDGER_LINES;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.jooq.DSLContext;
import org.jooq.InsertValuesStep4;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.ivarm1984.banksim.jooq.ledger.tables.records.LedgerLinesRecord;

import io.github.ivarm1984.banksim.account.AccountRepository;

@Service
public class LedgerService {

    private final DSLContext dsl;
    private final LedgerAccountService ledgerAccountService;
    private final AccountRepository accountRepository;

    public LedgerService(DSLContext dsl, LedgerAccountService ledgerAccountService, AccountRepository accountRepository) {
        this.dsl = dsl;
        this.ledgerAccountService = ledgerAccountService;
        this.accountRepository = accountRepository;
    }

    private record AffectedAccount(long accountId, EntryType entryType, BigDecimal amount) {
        BigDecimal delta() {
            return entryType == EntryType.CREDIT ? amount : amount.negate();
        }
    }

    /**
     * Posts a balanced journal entry: inserts the journal entry and its
     * lines, and updates the cached balance of every customer Account
     * touched by a CUSTOMER_LIABILITY line, all in one transaction.
     *
     * @throws UnbalancedJournalEntryException if total debits != total credits
     */
    @Transactional
    public long post(JournalEntryRequest request) {
        if (request.lines() == null || request.lines().isEmpty()) {
            throw new IllegalArgumentException("Journal entry must have at least one line");
        }

        BigDecimal totalDebits = sumByType(request.lines(), EntryType.DEBIT);
        BigDecimal totalCredits = sumByType(request.lines(), EntryType.CREDIT);
        if (totalDebits.compareTo(totalCredits) != 0) {
            throw new UnbalancedJournalEntryException(totalDebits, totalCredits);
        }

        // Resolve which ledger accounts back a customer Account, and lock
        // those Account rows in a fixed order before writing anything, so
        // concurrent postings against the same accounts can't deadlock.
        List<AffectedAccount> affected = request.lines().stream()
                .map(line -> {
                    LedgerAccount ledgerAccount = ledgerAccountService.findById(line.ledgerAccountId());
                    if (ledgerAccount.type() != LedgerAccountType.CUSTOMER_LIABILITY) {
                        return null;
                    }
                    return new AffectedAccount(ledgerAccount.accountId(), line.entryType(), line.amount());
                })
                .filter(java.util.Objects::nonNull)
                .toList();

        Map<Long, BigDecimal> prospectiveBalances = new TreeMap<>();
        new TreeSet<>(affected.stream().map(AffectedAccount::accountId).toList())
                .forEach(id -> prospectiveBalances.put(id, accountRepository.lockForUpdate(id).currentBalance()));

        // A customer liability account can never go negative - validate every
        // affected account against its cumulative deltas before writing
        // anything, so a rejected posting leaves no trace.
        for (AffectedAccount account : affected) {
            BigDecimal newBalance = prospectiveBalances.merge(account.accountId(), account.delta(), BigDecimal::add);
            if (newBalance.signum() < 0) {
                throw new InsufficientFundsException(account.accountId(), newBalance);
            }
        }

        long journalEntryId = dsl.insertInto(JOURNAL_ENTRIES)
                .set(JOURNAL_ENTRIES.DESCRIPTION, request.description())
                .returning(JOURNAL_ENTRIES.ID)
                .fetchOne()
                .getId();

        for (LedgerLineRequest line : request.lines()) {
            dsl.insertInto(LEDGER_LINES)
                    .set(LEDGER_LINES.JOURNAL_ENTRY_ID, journalEntryId)
                    .set(LEDGER_LINES.LEDGER_ACCOUNT_ID, line.ledgerAccountId())
                    .set(LEDGER_LINES.ENTRY_TYPE, line.entryType().name())
                    .set(LEDGER_LINES.AMOUNT, line.amount())
                    .execute();
        }

        // Liability accounts increase on CREDIT, decrease on DEBIT.
        for (AffectedAccount account : affected) {
            accountRepository.adjustBalance(account.accountId(), account.delta());
        }

        return journalEntryId;
    }

    /** One account's share of a {@link #postBulkAccountCredits} posting. */
    public record AccountCredit(long accountId, long ledgerAccountId, BigDecimal amount) {}

    /**
     * Posts one journal entry crediting many customer liability accounts at once - one
     * debit line for the total, one credit line per account - and bulk-updates their
     * cached balances via {@link io.github.ivarm1984.banksim.account.AccountRepository#adjustBalancesBatch}.
     *
     * <p>A deliberate bypass of {@link #post(JournalEntryRequest)}'s per-line
     * lookup/lock loop, which is itself O(lines) round trips even inside one
     * transaction - unusable at the account counts a daily batch job (see
     * {@code InterestAccrualScheduler}) needs to process. Safe only because every line
     * here is a known CUSTOMER_LIABILITY credit (a credit can never drive a liability
     * negative, so {@code post()}'s negative-balance guard doesn't apply) and the
     * caller guarantees no concurrent writer can race the whole batch.
     */
    @Transactional
    public long postBulkAccountCredits(String description, long debitLedgerAccountId, List<AccountCredit> credits) {
        if (credits.isEmpty()) {
            throw new IllegalArgumentException("credits must not be empty");
        }
        BigDecimal total = credits.stream().map(AccountCredit::amount).reduce(BigDecimal.ZERO, BigDecimal::add);

        long journalEntryId = dsl.insertInto(JOURNAL_ENTRIES)
                .set(JOURNAL_ENTRIES.DESCRIPTION, description)
                .returning(JOURNAL_ENTRIES.ID)
                .fetchOne()
                .getId();

        InsertValuesStep4<LedgerLinesRecord, Long, Long, String, BigDecimal> insert = dsl.insertInto(
                        LEDGER_LINES, LEDGER_LINES.JOURNAL_ENTRY_ID, LEDGER_LINES.LEDGER_ACCOUNT_ID,
                        LEDGER_LINES.ENTRY_TYPE, LEDGER_LINES.AMOUNT)
                .values(journalEntryId, debitLedgerAccountId, EntryType.DEBIT.name(), total);
        for (AccountCredit credit : credits) {
            insert = insert.values(journalEntryId, credit.ledgerAccountId(), EntryType.CREDIT.name(), credit.amount());
        }
        insert.execute();

        accountRepository.adjustBalancesBatch(
                credits.stream().collect(Collectors.toMap(AccountCredit::accountId, AccountCredit::amount)));

        return journalEntryId;
    }

    private static BigDecimal sumByType(List<LedgerLineRequest> lines, EntryType type) {
        return lines.stream()
                .filter(line -> line.entryType() == type)
                .map(LedgerLineRequest::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
