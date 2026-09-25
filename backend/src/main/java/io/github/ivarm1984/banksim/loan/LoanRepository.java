package io.github.ivarm1984.banksim.loan;

import static io.github.ivarm1984.banksim.jooq.loan.tables.LoanInstallments.LOAN_INSTALLMENTS;
import static io.github.ivarm1984.banksim.jooq.loan.tables.LoanPayments.LOAN_PAYMENTS;
import static io.github.ivarm1984.banksim.jooq.loan.tables.LoanPhaseHistory.LOAN_PHASE_HISTORY;
import static io.github.ivarm1984.banksim.jooq.loan.tables.LoanWriteOffs.LOAN_WRITE_OFFS;
import static io.github.ivarm1984.banksim.jooq.loan.tables.Loans.LOANS;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.max;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.sum;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.springframework.stereotype.Repository;

@Repository
public class LoanRepository {

    private final DSLContext dsl;

    public LoanRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public LoanAccount insertLoan(
            long customerId, long disbursementAccountId, LoanType loanType, BigDecimal principal, BigDecimal annualRate,
            int termMonths, BigDecimal installmentAmount, LocalDate originationDate) {
        var record = dsl.insertInto(LOANS)
                .set(LOANS.CUSTOMER_ID, customerId)
                .set(LOANS.DISBURSEMENT_ACCOUNT_ID, disbursementAccountId)
                .set(LOANS.LOAN_TYPE, loanType.name())
                .set(LOANS.PRINCIPAL, principal)
                .set(LOANS.ANNUAL_RATE, annualRate)
                .set(LOANS.TERM_MONTHS, termMonths)
                .set(LOANS.INSTALLMENT_AMOUNT, installmentAmount)
                .set(LOANS.ORIGINATION_DATE, originationDate)
                .set(LOANS.OUTSTANDING_PRINCIPAL, principal)
                .set(LOANS.NEXT_INSTALLMENT_NUMBER, 1)
                .set(LOANS.STATUS, LoanStatus.ACTIVE.name())
                .returning()
                .fetchOne();
        return toLoanAccount(record);
    }

    public void insertInstallment(
            long loanId, int installmentNumber, LocalDate dueDate, BigDecimal openingBalance,
            BigDecimal interestPortion, BigDecimal principalPortion, BigDecimal closingBalance) {
        dsl.insertInto(LOAN_INSTALLMENTS)
                .set(LOAN_INSTALLMENTS.LOAN_ID, loanId)
                .set(LOAN_INSTALLMENTS.INSTALLMENT_NUMBER, installmentNumber)
                .set(LOAN_INSTALLMENTS.DUE_DATE, dueDate)
                .set(LOAN_INSTALLMENTS.OPENING_BALANCE, openingBalance)
                .set(LOAN_INSTALLMENTS.INTEREST_PORTION, interestPortion)
                .set(LOAN_INSTALLMENTS.PRINCIPAL_PORTION, principalPortion)
                .set(LOAN_INSTALLMENTS.CLOSING_BALANCE, closingBalance)
                .execute();
    }

    public LoanPayment insertPayment(
            long loanId, LocalDate paymentDate, LoanPaymentType paymentType, BigDecimal amount,
            BigDecimal interestPortion, BigDecimal principalPortion, BigDecimal outstandingPrincipalAfter,
            Long journalEntryId) {
        var record = dsl.insertInto(LOAN_PAYMENTS)
                .set(LOAN_PAYMENTS.LOAN_ID, loanId)
                .set(LOAN_PAYMENTS.PAYMENT_DATE, paymentDate)
                .set(LOAN_PAYMENTS.PAYMENT_TYPE, paymentType.name())
                .set(LOAN_PAYMENTS.AMOUNT, amount)
                .set(LOAN_PAYMENTS.INTEREST_PORTION, interestPortion)
                .set(LOAN_PAYMENTS.PRINCIPAL_PORTION, principalPortion)
                .set(LOAN_PAYMENTS.OUTSTANDING_PRINCIPAL_AFTER, outstandingPrincipalAfter)
                .set(LOAN_PAYMENTS.JOURNAL_ENTRY_ID, journalEntryId)
                .returning()
                .fetchOne();
        return toLoanPayment(record);
    }

    /** Locks the loan row for update, so concurrent repayments against the same loan can't race. */
    public LoanAccount lockForUpdate(long loanId) {
        var record = dsl.selectFrom(LOANS)
                .where(LOANS.ID.eq(loanId))
                .forUpdate()
                .fetchOne();
        if (record == null) {
            throw new NoSuchElementException("No loan with id " + loanId);
        }
        return toLoanAccount(record);
    }

    public void updateAfterPayment(long loanId, BigDecimal outstandingPrincipal, int nextInstallmentNumber, LoanStatus status) {
        dsl.update(LOANS)
                .set(LOANS.OUTSTANDING_PRINCIPAL, outstandingPrincipal)
                .set(LOANS.NEXT_INSTALLMENT_NUMBER, nextInstallmentNumber)
                .set(LOANS.STATUS, status.name())
                .where(LOANS.ID.eq(loanId))
                .execute();
    }

    public LoanAccount findById(long id) {
        var record = dsl.selectFrom(LOANS)
                .where(LOANS.ID.eq(id))
                .fetchOne();
        if (record == null) {
            throw new NoSuchElementException("No loan with id " + id);
        }
        return toLoanAccount(record);
    }

    public List<LoanAccount> findByDisbursementAccountId(long accountId) {
        return dsl.selectFrom(LOANS)
                .where(LOANS.DISBURSEMENT_ACCOUNT_ID.eq(accountId))
                .orderBy(LOANS.ID)
                .fetch()
                .map(LoanRepository::toLoanAccount);
    }

    /** Active loans of one type - used by the daily phase-transition roll, which only ever targets BUSINESS loans (a small fraction of the book, no batching needed). */
    public List<LoanAccount> findActiveByLoanType(LoanType loanType) {
        return dsl.selectFrom(LOANS)
                .where(LOANS.LOAN_TYPE.eq(loanType.name()))
                .and(LOANS.STATUS.eq(LoanStatus.ACTIVE.name()))
                .orderBy(LOANS.ID)
                .fetch()
                .map(LoanRepository::toLoanAccount);
    }

    public void updatePhase(long loanId, LoanPhase phase, BigDecimal provisionAmount) {
        dsl.update(LOANS)
                .set(LOANS.PHASE, phase.name())
                .set(LOANS.PROVISION_AMOUNT, provisionAmount)
                .where(LOANS.ID.eq(loanId))
                .execute();
    }

    /** One active loan's staging inputs - see {@link #findActiveStagingInputs}. */
    public record StagingInput(long loanId, LoanPhase phase, LocalDate probationStartDate, int daysPastDue) {
    }

    /**
     * Every active loan's current stage plus its days past due as of {@code asOf}:
     * days since the due date of the oldest unpaid installment (installment
     * {@code next_installment_number}), 0 if that isn't due yet. One query for
     * the whole book - the daily staging pass only locks the loans whose stage
     * actually changes.
     */
    public List<StagingInput> findActiveStagingInputs(LocalDate asOf) {
        return dsl.select(LOANS.ID, LOANS.PHASE, LOANS.PROBATION_START_DATE, LOAN_INSTALLMENTS.DUE_DATE)
                .from(LOANS)
                .leftJoin(LOAN_INSTALLMENTS)
                .on(LOAN_INSTALLMENTS.LOAN_ID.eq(LOANS.ID))
                .and(LOAN_INSTALLMENTS.INSTALLMENT_NUMBER.eq(LOANS.NEXT_INSTALLMENT_NUMBER))
                .where(LOANS.STATUS.eq(LoanStatus.ACTIVE.name()))
                .orderBy(LOANS.ID)
                .fetch(r -> new StagingInput(
                        r.get(LOANS.ID), LoanPhase.valueOf(r.get(LOANS.PHASE)), r.get(LOANS.PROBATION_START_DATE),
                        daysPastDue(r.get(LOAN_INSTALLMENTS.DUE_DATE), asOf)));
    }

    /** Days past due for one loan - same definition as {@link #findActiveStagingInputs}. */
    public int daysPastDue(LoanAccount loan, LocalDate asOf) {
        LocalDate dueDate = dsl.select(LOAN_INSTALLMENTS.DUE_DATE)
                .from(LOAN_INSTALLMENTS)
                .where(LOAN_INSTALLMENTS.LOAN_ID.eq(loan.id()))
                .and(LOAN_INSTALLMENTS.INSTALLMENT_NUMBER.eq(loan.nextInstallmentNumber()))
                .fetchOne(LOAN_INSTALLMENTS.DUE_DATE);
        return daysPastDue(dueDate, asOf);
    }

    private static int daysPastDue(LocalDate oldestUnpaidDueDate, LocalDate asOf) {
        if (oldestUnpaidDueDate == null) {
            return 0;
        }
        return (int) Math.max(0, ChronoUnit.DAYS.between(oldestUnpaidDueDate, asOf));
    }

    /** Locks every active loan row - used by the whole-book ECL remeasurement so it can't race a repayment's own remeasure. */
    public List<LoanAccount> lockAllActiveForUpdate() {
        return dsl.selectFrom(LOANS)
                .where(LOANS.STATUS.eq(LoanStatus.ACTIVE.name()))
                .orderBy(LOANS.ID)
                .forUpdate()
                .fetch()
                .map(LoanRepository::toLoanAccount);
    }

    public void updateStaging(long loanId, LoanPhase phase, BigDecimal provisionAmount, LocalDate probationStartDate) {
        dsl.update(LOANS)
                .set(LOANS.PHASE, phase.name())
                .set(LOANS.PROVISION_AMOUNT, provisionAmount)
                .set(LOANS.PROBATION_START_DATE, probationStartDate)
                .where(LOANS.ID.eq(loanId))
                .execute();
    }

    public void batchUpdateProvisions(Map<Long, BigDecimal> provisionByLoanId) {
        dsl.batch(provisionByLoanId.entrySet().stream()
                        .map(e -> dsl.update(LOANS)
                                .set(LOANS.PROVISION_AMOUNT, e.getValue())
                                .where(LOANS.ID.eq(e.getKey())))
                        .toList())
                .execute();
    }

    /** Active book aggregated per product and stage - what treasury's credit-risk RWA and collateral figures are built from. */
    public List<CreditExposure> activeExposuresByTypeAndPhase() {
        var outstanding = sum(LOANS.OUTSTANDING_PRINCIPAL);
        var provision = sum(LOANS.PROVISION_AMOUNT);
        return dsl.select(LOANS.LOAN_TYPE, LOANS.PHASE, outstanding, provision)
                .from(LOANS)
                .where(LOANS.STATUS.eq(LoanStatus.ACTIVE.name()))
                .groupBy(LOANS.LOAN_TYPE, LOANS.PHASE)
                .fetch(r -> new CreditExposure(
                        LoanType.valueOf(r.get(LOANS.LOAN_TYPE)), LoanPhase.valueOf(r.get(LOANS.PHASE)),
                        r.get(outstanding), r.get(provision)));
    }

    public void updateProvision(long loanId, BigDecimal provisionAmount) {
        dsl.update(LOANS)
                .set(LOANS.PROVISION_AMOUNT, provisionAmount)
                .where(LOANS.ID.eq(loanId))
                .execute();
    }

    public LoanPhaseTransition insertPhaseHistory(
            long loanId, LoanPhase fromPhase, LoanPhase toPhase, LocalDate transitionDate, BigDecimal provisionDelta,
            Long journalEntryId) {
        var record = dsl.insertInto(LOAN_PHASE_HISTORY)
                .set(LOAN_PHASE_HISTORY.LOAN_ID, loanId)
                .set(LOAN_PHASE_HISTORY.FROM_PHASE, fromPhase.name())
                .set(LOAN_PHASE_HISTORY.TO_PHASE, toPhase.name())
                .set(LOAN_PHASE_HISTORY.TRANSITION_DATE, transitionDate)
                .set(LOAN_PHASE_HISTORY.PROVISION_DELTA, provisionDelta)
                .set(LOAN_PHASE_HISTORY.JOURNAL_ENTRY_ID, journalEntryId)
                .returning()
                .fetchOne();
        return toLoanPhaseTransition(record);
    }

    public List<LoanPhaseTransition> findPhaseHistoryByLoanId(long loanId) {
        return dsl.selectFrom(LOAN_PHASE_HISTORY)
                .where(LOAN_PHASE_HISTORY.LOAN_ID.eq(loanId))
                .orderBy(LOAN_PHASE_HISTORY.ID)
                .fetch()
                .map(LoanRepository::toLoanPhaseTransition);
    }

    /** One defaulted loan's write-off inputs - see {@link #findDefaultedLoans}. */
    public record DefaultedLoan(long loanId, LoanType loanType, LocalDate defaultedSince, LocalDate probationStartDate) {
    }

    /**
     * Every active Stage 3 loan with the date it last entered default (its
     * latest transition into NON_PERFORMING - a loan that cured and
     * re-defaulted starts over). One query for the whole book; the daily
     * write-off pass only locks the loans actually due.
     */
    public List<DefaultedLoan> findDefaultedLoans() {
        Field<LocalDate> defaultedSince = defaultedSince();
        return dsl.select(LOANS.ID, LOANS.LOAN_TYPE, LOANS.PROBATION_START_DATE, defaultedSince)
                .from(LOANS)
                .where(LOANS.STATUS.eq(LoanStatus.ACTIVE.name()))
                .and(LOANS.PHASE.eq(LoanPhase.NON_PERFORMING.name()))
                .orderBy(LOANS.ID)
                .fetch(r -> new DefaultedLoan(
                        r.get(LOANS.ID), LoanType.valueOf(r.get(LOANS.LOAN_TYPE)), r.get(defaultedSince),
                        r.get(LOANS.PROBATION_START_DATE)));
    }

    /** Same definition as {@link #findDefaultedLoans}, for one loan; null if it has never defaulted. */
    public LocalDate defaultedSince(long loanId) {
        return dsl.select(max(LOAN_PHASE_HISTORY.TRANSITION_DATE))
                .from(LOAN_PHASE_HISTORY)
                .where(LOAN_PHASE_HISTORY.LOAN_ID.eq(loanId))
                .and(LOAN_PHASE_HISTORY.TO_PHASE.eq(LoanPhase.NON_PERFORMING.name()))
                .fetchOne(0, LocalDate.class);
    }

    private static Field<LocalDate> defaultedSince() {
        return field(select(max(LOAN_PHASE_HISTORY.TRANSITION_DATE))
                .from(LOAN_PHASE_HISTORY)
                .where(LOAN_PHASE_HISTORY.LOAN_ID.eq(LOANS.ID))
                .and(LOAN_PHASE_HISTORY.TO_PHASE.eq(LoanPhase.NON_PERFORMING.name())))
                .as("defaulted_since");
    }

    /** Derecognises the loan: no receivable and no allowance left on the book. */
    public void markWrittenOff(long loanId) {
        dsl.update(LOANS)
                .set(LOANS.STATUS, LoanStatus.WRITTEN_OFF.name())
                .set(LOANS.OUTSTANDING_PRINCIPAL, BigDecimal.ZERO)
                .set(LOANS.PROVISION_AMOUNT, BigDecimal.ZERO)
                .set(LOANS.PROBATION_START_DATE, (LocalDate) null)
                .where(LOANS.ID.eq(loanId))
                .execute();
    }

    public LoanWriteOff insertWriteOff(
            long loanId, LocalDate writeOffDate, LocalDate defaultedSince, BigDecimal outstandingPrincipal,
            BigDecimal recoveryAmount, BigDecimal writtenOffAmount, BigDecimal allowanceUsed, long journalEntryId) {
        var record = dsl.insertInto(LOAN_WRITE_OFFS)
                .set(LOAN_WRITE_OFFS.LOAN_ID, loanId)
                .set(LOAN_WRITE_OFFS.WRITE_OFF_DATE, writeOffDate)
                .set(LOAN_WRITE_OFFS.DEFAULTED_SINCE, defaultedSince)
                .set(LOAN_WRITE_OFFS.OUTSTANDING_PRINCIPAL, outstandingPrincipal)
                .set(LOAN_WRITE_OFFS.RECOVERY_AMOUNT, recoveryAmount)
                .set(LOAN_WRITE_OFFS.WRITTEN_OFF_AMOUNT, writtenOffAmount)
                .set(LOAN_WRITE_OFFS.ALLOWANCE_USED, allowanceUsed)
                .set(LOAN_WRITE_OFFS.JOURNAL_ENTRY_ID, journalEntryId)
                .returning()
                .fetchOne();
        return toLoanWriteOff(record);
    }

    public Optional<LoanWriteOff> findWriteOffByLoanId(long loanId) {
        return dsl.selectFrom(LOAN_WRITE_OFFS)
                .where(LOAN_WRITE_OFFS.LOAN_ID.eq(loanId))
                .fetchOptional()
                .map(LoanRepository::toLoanWriteOff);
    }

    public List<LoanInstallment> findInstallmentsByLoanId(long loanId) {
        return dsl.selectFrom(LOAN_INSTALLMENTS)
                .where(LOAN_INSTALLMENTS.LOAN_ID.eq(loanId))
                .orderBy(LOAN_INSTALLMENTS.INSTALLMENT_NUMBER)
                .fetch()
                .map(LoanRepository::toLoanInstallment);
    }

    public List<LoanPayment> findPaymentsByLoanId(long loanId) {
        return dsl.selectFrom(LOAN_PAYMENTS)
                .where(LOAN_PAYMENTS.LOAN_ID.eq(loanId))
                .orderBy(LOAN_PAYMENTS.ID)
                .fetch()
                .map(LoanRepository::toLoanPayment);
    }

    private static LoanAccount toLoanAccount(io.github.ivarm1984.banksim.jooq.loan.tables.records.LoansRecord record) {
        return new LoanAccount(
                record.getId(),
                record.getCustomerId(),
                record.getDisbursementAccountId(),
                LoanType.valueOf(record.getLoanType()),
                record.getPrincipal(),
                record.getAnnualRate(),
                record.getTermMonths(),
                record.getInstallmentAmount(),
                record.getOriginationDate(),
                record.getOutstandingPrincipal(),
                record.getNextInstallmentNumber(),
                LoanStatus.valueOf(record.getStatus()),
                LoanPhase.valueOf(record.getPhase()),
                record.getProvisionAmount(),
                record.getProbationStartDate(),
                record.getCreatedAt());
    }

    private static LoanInstallment toLoanInstallment(io.github.ivarm1984.banksim.jooq.loan.tables.records.LoanInstallmentsRecord record) {
        return new LoanInstallment(
                record.getId(),
                record.getLoanId(),
                record.getInstallmentNumber(),
                record.getDueDate(),
                record.getOpeningBalance(),
                record.getInterestPortion(),
                record.getPrincipalPortion(),
                record.getClosingBalance());
    }

    private static LoanPayment toLoanPayment(io.github.ivarm1984.banksim.jooq.loan.tables.records.LoanPaymentsRecord record) {
        return new LoanPayment(
                record.getId(),
                record.getLoanId(),
                record.getPaymentDate(),
                LoanPaymentType.valueOf(record.getPaymentType()),
                record.getAmount(),
                record.getInterestPortion(),
                record.getPrincipalPortion(),
                record.getOutstandingPrincipalAfter(),
                record.getJournalEntryId(),
                record.getCreatedAt());
    }

    private static LoanWriteOff toLoanWriteOff(io.github.ivarm1984.banksim.jooq.loan.tables.records.LoanWriteOffsRecord record) {
        return new LoanWriteOff(
                record.getId(),
                record.getLoanId(),
                record.getWriteOffDate(),
                record.getDefaultedSince(),
                record.getOutstandingPrincipal(),
                record.getRecoveryAmount(),
                record.getWrittenOffAmount(),
                record.getAllowanceUsed(),
                record.getJournalEntryId(),
                record.getCreatedAt());
    }

    private static LoanPhaseTransition toLoanPhaseTransition(io.github.ivarm1984.banksim.jooq.loan.tables.records.LoanPhaseHistoryRecord record) {
        return new LoanPhaseTransition(
                record.getId(),
                record.getLoanId(),
                LoanPhase.valueOf(record.getFromPhase()),
                LoanPhase.valueOf(record.getToPhase()),
                record.getTransitionDate(),
                record.getProvisionDelta(),
                record.getJournalEntryId(),
                record.getCreatedAt());
    }
}
