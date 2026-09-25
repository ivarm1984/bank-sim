package io.github.ivarm1984.banksim.loan;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.ivarm1984.banksim.event.DomainEventPublisher;
import io.github.ivarm1984.banksim.ledger.EntryType;
import io.github.ivarm1984.banksim.ledger.JournalEntryRequest;
import io.github.ivarm1984.banksim.ledger.LedgerAccountService;
import io.github.ivarm1984.banksim.ledger.LedgerAccountType;
import io.github.ivarm1984.banksim.ledger.LedgerLineRequest;
import io.github.ivarm1984.banksim.ledger.LedgerService;

/**
 * Writes off defaulted loans that never cured (IFRS 9 5.4.4): once a loan
 * has sat in Stage 3 for its product's horizon (see
 * {@link CreditRisk#dueForWriteOff}) the bank sells the collateral or the
 * debt, recovers {@code (1 - LGD) x outstanding} (see
 * {@link CreditRisk#recoveryAmount}), and charges the rest against the loss
 * allowance - one journal entry:
 *
 * <pre>
 *   Debit  CENTRAL_BANK_RESERVES  recovery (settled via the buyer's bank)
 *   Debit  LOAN_LOSS_PROVISION    the loan's allowance, released in full
 *   Credit LOAN_RECEIVABLE        outstanding principal, derecognised
 *   Debit/Credit PROVISION_EXPENSE  written-off amount less the allowance
 * </pre>
 *
 * The Stage 3 allowance is already {@code LGD x outstanding}, so the
 * PROVISION_EXPENSE leg is normally zero (rounding aside) - the loss was
 * recognised as the allowance built up, not at write-off. The loan becomes
 * {@link LoanStatus#WRITTEN_OFF} and leaves the active book, so it drops
 * out of the NPL ratio, RWA and the collateral pool.
 * {@code EventInjectorScheduler} calls {@link #writeOffDaily} once per
 * simulated day, after the staging pass.
 */
@Service
public class LoanWriteOffService {

    private static final Logger log = LoggerFactory.getLogger(LoanWriteOffService.class);

    private final LoanRepository loanRepository;
    private final LedgerService ledgerService;
    private final LedgerAccountService ledgerAccountService;
    private final DomainEventPublisher events;
    private final TransactionTemplate transactionTemplate;

    public LoanWriteOffService(
            LoanRepository loanRepository, LedgerService ledgerService, LedgerAccountService ledgerAccountService,
            DomainEventPublisher events, TransactionTemplate transactionTemplate) {
        this.loanRepository = loanRepository;
        this.ledgerService = ledgerService;
        this.ledgerAccountService = ledgerAccountService;
        this.events = events;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Writes off every defaulted loan that's due, one transaction each - a
     * failure on one loan (logged and skipped) never rolls back the others.
     * The locked row is re-checked from scratch: a repayment that landed in
     * between may have put it on probation.
     */
    public void writeOffDaily(LocalDate date) {
        for (LoanRepository.DefaultedLoan loan : loanRepository.findDefaultedLoans()) {
            if (!CreditRisk.dueForWriteOff(loan.loanType(), loan.defaultedSince(), loan.probationStartDate(), date)) {
                continue;
            }
            try {
                writeOffIfDue(loan.loanId(), date);
            } catch (RuntimeException e) {
                log.warn("Write-off failed for loan {} on {}", loan.loanId(), date, e);
            }
        }
    }

    /** One loan's write-off, in its own transaction - package-visible so tests can target just their own loan in the shared DB. */
    void writeOffIfDue(long loanId, LocalDate date) {
        transactionTemplate.executeWithoutResult(status -> writeOff(loanId, date));
    }

    private void writeOff(long loanId, LocalDate date) {
        LoanAccount loan = loanRepository.lockForUpdate(loanId);
        LocalDate defaultedSince = loanRepository.defaultedSince(loanId);
        if (loan.status() != LoanStatus.ACTIVE || loan.phase() != LoanPhase.NON_PERFORMING
                || !CreditRisk.dueForWriteOff(loan.loanType(), defaultedSince, loan.probationStartDate(), date)) {
            return;
        }

        BigDecimal outstanding = loan.outstandingPrincipal();
        BigDecimal recovery = CreditRisk.recoveryAmount(loan.loanType(), outstanding);
        BigDecimal writtenOff = outstanding.subtract(recovery);
        BigDecimal allowance = loan.provisionAmount();
        BigDecimal expense = writtenOff.subtract(allowance);

        List<LedgerLineRequest> lines = new ArrayList<>();
        addLine(lines, LedgerAccountType.CENTRAL_BANK_RESERVES, EntryType.DEBIT, recovery);
        addLine(lines, LedgerAccountType.LOAN_LOSS_PROVISION, EntryType.DEBIT, allowance);
        addLine(lines, LedgerAccountType.PROVISION_EXPENSE, expense.signum() > 0 ? EntryType.DEBIT : EntryType.CREDIT, expense.abs());
        long loanReceivableId = ledgerAccountService.findLoanReceivableAccount(loanId).id();
        lines.add(new LedgerLineRequest(loanReceivableId, EntryType.CREDIT, outstanding));
        long journalEntryId = ledgerService.post(new JournalEntryRequest(
                "Write-off of loan " + loanId + " (in default since " + defaultedSince + ")", lines));

        loanRepository.markWrittenOff(loanId);
        loanRepository.insertWriteOff(loanId, date, defaultedSince, outstanding, recovery, writtenOff, allowance, journalEntryId);
        log.info("Loan {} written off on {}: outstanding {}, recovered {}, charged {} against allowance {}",
                loanId, date, outstanding, recovery, writtenOff, allowance);
        events.publish(new LoanWrittenOffEvent(
                loanId, loan.loanType(), date, outstanding, recovery, writtenOff, journalEntryId));
    }

    /** Ledger lines must be strictly positive - a zero leg (e.g. no rounding difference) is left out. */
    private void addLine(List<LedgerLineRequest> lines, LedgerAccountType singleton, EntryType side, BigDecimal amount) {
        if (amount.signum() > 0) {
            lines.add(new LedgerLineRequest(ledgerAccountService.getSingleton(singleton).id(), side, amount));
        }
    }
}
