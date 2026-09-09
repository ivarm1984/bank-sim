package io.github.ivarm1984.banksim.loan;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.ivarm1984.banksim.event.DomainEventPublisher;
import io.github.ivarm1984.banksim.ledger.EntryType;
import io.github.ivarm1984.banksim.ledger.JournalEntryRequest;
import io.github.ivarm1984.banksim.ledger.LedgerAccountService;
import io.github.ivarm1984.banksim.ledger.LedgerAccountType;
import io.github.ivarm1984.banksim.ledger.LedgerLineRequest;
import io.github.ivarm1984.banksim.ledger.LedgerService;

/**
 * Daily loan-loss provisioning phase state machine for {@link LoanType#BUSINESS}
 * loans - {@code EventInjectorScheduler} drives {@link #rollDailyTransitions}
 * once per simulated day, passing whether a recession is currently active
 * (see {@code eventinjector.RecessionShockService}). MORTGAGE/CONSUMER loans
 * never appear here - {@link LoanRepository#findActiveByLoanType} is always
 * called with {@link LoanType#BUSINESS}.
 *
 * <p>Deliberately a lighter accounting-only stand-in, not the full
 * delinquency/NPL simulation TODO.md's "Complex additions" section defers
 * separately - a NON_PERFORMING loan still amortizes/repays normally through
 * {@code LoanService.repay}, unaffected by its phase.
 *
 * <p>Provisioning is posted to singleton ledger accounts (LOAN_LOSS_PROVISION /
 * PROVISION_EXPENSE), not a per-loan account - {@code ledger_accounts}' unique
 * index on {@code loan_id} already allows only one loan-tagged row per loan,
 * claimed by LOAN_RECEIVABLE. Per-loan provision amount instead lives as a
 * plain column, {@code loans.provision_amount}.
 */
@Service
public class LoanPhaseTransitionService {

    private static final Logger log = LoggerFactory.getLogger(LoanPhaseTransitionService.class);

    /** Nonzero even outside a recession - real business loans carry some risk regardless of the macro cycle. */
    static final double BASELINE_DOWNGRADE_DAILY_PROBABILITY = 0.0005;
    /** 8x the baseline while a recession is active (see eventinjector.RecessionShockService). */
    static final double RECESSION_DOWNGRADE_DAILY_PROBABILITY = 0.0040;
    /** Recovery/upgrade probability, independent of recession state. */
    static final double RECOVERY_DAILY_PROBABILITY = 0.0100;

    static final BigDecimal UNDERPERFORMING_PROVISION_RATE = new BigDecimal("0.10");
    static final BigDecimal NON_PERFORMING_PROVISION_RATE = new BigDecimal("0.50");

    private final LoanRepository loanRepository;
    private final LedgerService ledgerService;
    private final LedgerAccountService ledgerAccountService;
    private final DomainEventPublisher events;
    private final Random random;

    @Autowired
    public LoanPhaseTransitionService(
            LoanRepository loanRepository, LedgerService ledgerService, LedgerAccountService ledgerAccountService,
            DomainEventPublisher events) {
        this(loanRepository, ledgerService, ledgerAccountService, events, new Random());
    }

    /** Test seam - lets tests force/deny transitions deterministically. */
    LoanPhaseTransitionService(
            LoanRepository loanRepository, LedgerService ledgerService, LedgerAccountService ledgerAccountService,
            DomainEventPublisher events, Random random) {
        this.loanRepository = loanRepository;
        this.ledgerService = ledgerService;
        this.ledgerAccountService = ledgerAccountService;
        this.events = events;
        this.random = random;
    }

    @Transactional
    public void rollDailyTransitions(LocalDate date, boolean recessionActive) {
        List<LoanAccount> businessLoans = loanRepository.findActiveByLoanType(LoanType.BUSINESS);
        for (LoanAccount loan : businessLoans) {
            LoanPhase next = nextPhase(loan.phase(), recessionActive, random.nextDouble(), random.nextDouble());
            if (next != loan.phase()) {
                applyTransition(loan, next, date);
            }
        }
    }

    private void applyTransition(LoanAccount loan, LoanPhase newPhase, LocalDate date) {
        BigDecimal newProvision = provisionAmount(newPhase, loan.outstandingPrincipal());
        BigDecimal delta = newProvision.subtract(loan.provisionAmount());
        Long journalEntryId = null;
        if (delta.signum() != 0) {
            long expenseId = ledgerAccountService.getSingleton(LedgerAccountType.PROVISION_EXPENSE).id();
            long provisionId = ledgerAccountService.getSingleton(LedgerAccountType.LOAN_LOSS_PROVISION).id();
            BigDecimal amount = delta.abs();
            EntryType expenseSide = delta.signum() > 0 ? EntryType.DEBIT : EntryType.CREDIT;
            EntryType provisionSide = delta.signum() > 0 ? EntryType.CREDIT : EntryType.DEBIT;
            journalEntryId = ledgerService.post(new JournalEntryRequest(
                    "Loan " + loan.id() + " phase " + loan.phase() + " -> " + newPhase + " provision adjustment",
                    List.of(
                            new LedgerLineRequest(expenseId, expenseSide, amount),
                            new LedgerLineRequest(provisionId, provisionSide, amount))));
        }

        loanRepository.updatePhase(loan.id(), newPhase, newProvision);
        loanRepository.insertPhaseHistory(loan.id(), loan.phase(), newPhase, date, delta, journalEntryId);
        log.info("Loan {} phase {} -> {} (provision {} -> {})", loan.id(), loan.phase(), newPhase, loan.provisionAmount(), newProvision);
        events.publish(new LoanPhaseChangedEvent(loan.id(), loan.phase(), newPhase, date, delta, newProvision, journalEntryId));
    }

    /** Pure phase state machine - see LoanPhaseTransitionLogicTest. */
    static LoanPhase nextPhase(LoanPhase current, boolean recessionActive, double downgradeRoll, double upgradeRoll) {
        if (current != LoanPhase.NON_PERFORMING) {
            double downgradeProbability = recessionActive ? RECESSION_DOWNGRADE_DAILY_PROBABILITY : BASELINE_DOWNGRADE_DAILY_PROBABILITY;
            if (downgradeRoll < downgradeProbability) {
                return current == LoanPhase.PERFORMING ? LoanPhase.UNDERPERFORMING : LoanPhase.NON_PERFORMING;
            }
        }
        if (current != LoanPhase.PERFORMING && upgradeRoll < RECOVERY_DAILY_PROBABILITY) {
            return current == LoanPhase.NON_PERFORMING ? LoanPhase.UNDERPERFORMING : LoanPhase.PERFORMING;
        }
        return current;
    }

    /** Pure provisioning-amount formula - see LoanPhaseTransitionLogicTest. */
    static BigDecimal provisionAmount(LoanPhase phase, BigDecimal outstandingPrincipal) {
        BigDecimal rate = switch (phase) {
            case PERFORMING -> BigDecimal.ZERO;
            case UNDERPERFORMING -> UNDERPERFORMING_PROVISION_RATE;
            case NON_PERFORMING -> NON_PERFORMING_PROVISION_RATE;
        };
        return outstandingPrincipal.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }
}
