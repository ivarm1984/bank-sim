package io.github.ivarm1984.banksim.loan;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.ivarm1984.banksim.event.DomainEventPublisher;

/**
 * IFRS 9 staging and loss allowance for every loan type, driven by actual
 * payment behaviour rather than a random roll: a loan's stage follows its
 * days past due (see {@link CreditRisk#nextStaging}), and its provision is
 * its expected credit loss for that stage (see
 * {@link CreditRisk#expectedCreditLoss}). {@code EventInjectorScheduler}
 * calls {@link #evaluateDaily} once per simulated day, and
 * {@link #remeasureAll} whenever a recession starts or ends (the macro
 * scenario the forward-looking PDs depend on changed).
 *
 * <p>Provisioning is posted to singleton ledger accounts (LOAN_LOSS_PROVISION /
 * PROVISION_EXPENSE), not a per-loan account - {@code ledger_accounts}' unique
 * index on {@code loan_id} already allows only one loan-tagged row per loan,
 * claimed by LOAN_RECEIVABLE. The per-loan allowance lives in
 * {@code loans.provision_amount}.
 */
@Service
public class LoanStagingService {

    private static final Logger log = LoggerFactory.getLogger(LoanStagingService.class);

    private final LoanRepository loanRepository;
    private final LoanProvisionPoster provisionPoster;
    private final DomainEventPublisher events;
    private final TransactionTemplate transactionTemplate;

    public LoanStagingService(
            LoanRepository loanRepository, LoanProvisionPoster provisionPoster, DomainEventPublisher events,
            TransactionTemplate transactionTemplate) {
        this.loanRepository = loanRepository;
        this.provisionPoster = provisionPoster;
        this.events = events;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Restages every active loan whose days past due moved it across a stage
     * boundary (or into/out of default probation). The whole book is read in
     * one query; only loans that actually change are then locked and updated,
     * one transaction each, so a failure on one loan (logged and skipped)
     * never rolls back the others. The locked row is re-staged from scratch -
     * a repayment that landed in between may have cured it.
     */
    public void evaluateDaily(LocalDate date, boolean recessionActive) {
        for (LoanRepository.StagingInput input : loanRepository.findActiveStagingInputs(date)) {
            CreditRisk.Staging next = CreditRisk.nextStaging(input.phase(), input.probationStartDate(), input.daysPastDue(), date);
            if (unchanged(next, input.phase(), input.probationStartDate())) {
                continue;
            }
            try {
                evaluateLoan(input.loanId(), date, recessionActive);
            } catch (RuntimeException e) {
                log.warn("Staging failed for loan {} on {}", input.loanId(), date, e);
            }
        }
    }

    /** One loan's restage, in its own transaction - package-visible so tests can stage just their own loan in the shared DB. */
    void evaluateLoan(long loanId, LocalDate date, boolean recessionActive) {
        transactionTemplate.executeWithoutResult(status -> restage(loanId, date, recessionActive));
    }

    private void restage(long loanId, LocalDate date, boolean recessionActive) {
        LoanAccount loan = loanRepository.lockForUpdate(loanId);
        if (loan.status() != LoanStatus.ACTIVE) {
            return;
        }
        int daysPastDue = loanRepository.daysPastDue(loan, date);
        CreditRisk.Staging next = CreditRisk.nextStaging(loan.phase(), loan.probationStartDate(), daysPastDue, date);
        if (unchanged(next, loan.phase(), loan.probationStartDate())) {
            return;
        }

        BigDecimal newProvision = CreditRisk.expectedCreditLoss(
                loan.loanType(), next.phase(), loan.outstandingPrincipal(), CreditRisk.remainingMonths(loan), recessionActive);
        BigDecimal delta = newProvision.subtract(loan.provisionAmount());
        Long journalEntryId = provisionPoster.post(
                delta, "Loan " + loan.id() + " stage " + loan.phase() + " -> " + next.phase() + " ECL remeasurement");
        loanRepository.updateStaging(loan.id(), next.phase(), newProvision, next.probationStart());

        if (next.phase() != loan.phase()) {
            loanRepository.insertPhaseHistory(loan.id(), loan.phase(), next.phase(), date, delta, journalEntryId);
            log.info("Loan {} stage {} -> {} at {} DPD (provision {} -> {})",
                    loan.id(), loan.phase(), next.phase(), daysPastDue, loan.provisionAmount(), newProvision);
            events.publish(new LoanPhaseChangedEvent(loan.id(), loan.phase(), next.phase(), date, delta, newProvision, journalEntryId));
        }
    }

    private static boolean unchanged(CreditRisk.Staging next, LoanPhase phase, LocalDate probationStart) {
        return next.phase() == phase && Objects.equals(next.probationStart(), probationStart);
    }

    /**
     * Remeasures every active loan's ECL under a new macro scenario and posts
     * the net change as one journal entry. Locks the whole active book for
     * the duration, so it can't interleave with a repayment's own per-loan
     * remeasure. Rare - once per recession start/end.
     */
    @Transactional
    public BigDecimal remeasureAll(LocalDate date, boolean recessionActive) {
        List<LoanAccount> loans = loanRepository.lockAllActiveForUpdate();
        Map<Long, BigDecimal> changed = new HashMap<>();
        BigDecimal netDelta = BigDecimal.ZERO;
        for (LoanAccount loan : loans) {
            BigDecimal ecl = CreditRisk.expectedCreditLoss(
                    loan.loanType(), loan.phase(), loan.outstandingPrincipal(), CreditRisk.remainingMonths(loan), recessionActive);
            BigDecimal delta = ecl.subtract(loan.provisionAmount());
            if (delta.signum() != 0) {
                changed.put(loan.id(), ecl);
                netDelta = netDelta.add(delta);
            }
        }
        if (!changed.isEmpty()) {
            loanRepository.batchUpdateProvisions(changed);
        }
        provisionPoster.post(netDelta, "Book-wide ECL remeasurement on " + date + " (recession "
                + (recessionActive ? "active" : "over") + ", " + changed.size() + " loans)");
        log.info("ECL remeasured for {} loans on {} (recession {}): net provision change {}",
                changed.size(), date, recessionActive, netDelta);
        return netDelta;
    }
}
