package io.github.ivarm1984.banksim.ledger;

import static io.github.ivarm1984.banksim.jooq.ledger.tables.JournalEntries.JOURNAL_ENTRIES;
import static io.github.ivarm1984.banksim.jooq.ledger.tables.LedgerLines.LEDGER_LINES;

import java.math.BigDecimal;
import java.util.List;
import java.util.TreeSet;

import org.jooq.DSLContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
        record AffectedAccount(long accountId, EntryType entryType, BigDecimal amount) {
        }
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

        new TreeSet<>(affected.stream().map(AffectedAccount::accountId).toList())
                .forEach(accountRepository::lockForUpdate);

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
            BigDecimal delta = account.entryType() == EntryType.CREDIT ? account.amount() : account.amount().negate();
            accountRepository.adjustBalance(account.accountId(), delta);
        }

        return journalEntryId;
    }

    private static BigDecimal sumByType(List<LedgerLineRequest> lines, EntryType type) {
        return lines.stream()
                .filter(line -> line.entryType() == type)
                .map(LedgerLineRequest::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
