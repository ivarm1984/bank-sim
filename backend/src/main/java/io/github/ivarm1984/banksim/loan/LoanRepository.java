package io.github.ivarm1984.banksim.loan;

import static io.github.ivarm1984.banksim.jooq.loan.tables.LoanInstallments.LOAN_INSTALLMENTS;
import static io.github.ivarm1984.banksim.jooq.loan.tables.LoanPayments.LOAN_PAYMENTS;
import static io.github.ivarm1984.banksim.jooq.loan.tables.Loans.LOANS;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;

import org.jooq.DSLContext;
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
}
