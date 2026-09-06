package io.github.ivarm1984.banksim.loan;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.ivarm1984.banksim.centralbank.CentralBankService;
import io.github.ivarm1984.banksim.clock.ClockService;
import io.github.ivarm1984.banksim.event.DomainEventPublisher;
import io.github.ivarm1984.banksim.ledger.EntryType;
import io.github.ivarm1984.banksim.ledger.JournalEntryRequest;
import io.github.ivarm1984.banksim.ledger.LedgerAccountService;
import io.github.ivarm1984.banksim.ledger.LedgerAccountType;
import io.github.ivarm1984.banksim.ledger.LedgerLineRequest;
import io.github.ivarm1984.banksim.ledger.LedgerService;

/**
 * Loan origination, disbursement, and repayment (including partial/full
 * early payoff).
 *
 * <p>Amortization uses a nominal monthly periodic rate ({@code annualRate /
 * 12}), not the day-count (actual/365) convention {@code InterestAccrualService}
 * uses for savings/checking interest - that's deliberate: monthly-amortizing
 * loans conventionally price off a nominal periodic rate, independent of how
 * many calendar days actually fall in a given month.
 *
 * <p>Prepayment follows a "term reduction" model: {@code installmentAmount}
 * (fixed at origination) never changes. Extra principal paid early just
 * lowers {@code outstandingPrincipal} faster than the original plan, which
 * is why later installments' interest comes out lower than planned and the
 * loan finishes ahead of {@code termMonths}. {@code loan_installments} is
 * therefore always the *original*, unmodified plan - useful for
 * hand-verification but not a live view of what's actually owed once a
 * prepayment has happened; {@code loans.outstanding_principal} is the
 * source of truth for that.
 */
@Service
public class LoanService {

    /**
     * Flat spread added to the central bank policy rate for every loan.
     * Real per-borrower credit risk pricing is deferred - see TODO.md's
     * "Complex additions" section ("Credit risk pricing").
     */
    static final BigDecimal RISK_SPREAD = new BigDecimal("0.0400");

    private static final BigDecimal MONTHS_PER_YEAR = new BigDecimal("12");

    private final LoanRepository loanRepository;
    private final LedgerService ledgerService;
    private final LedgerAccountService ledgerAccountService;
    private final CentralBankService centralBankService;
    private final ClockService clockService;
    private final DomainEventPublisher events;

    public LoanService(
            LoanRepository loanRepository, LedgerService ledgerService, LedgerAccountService ledgerAccountService,
            CentralBankService centralBankService, ClockService clockService, DomainEventPublisher events) {
        this.loanRepository = loanRepository;
        this.ledgerService = ledgerService;
        this.ledgerAccountService = ledgerAccountService;
        this.centralBankService = centralBankService;
        this.clockService = clockService;
        this.events = events;
    }

    /**
     * Approves a loan: computes its fixed rate and installment amount and
     * persists the full amortization schedule. No money moves yet - see
     * {@link #disburse(long)}.
     */
    @Transactional
    public LoanAccount originate(long customerId, long disbursementAccountId, BigDecimal principal, int termMonths) {
        if (principal == null || principal.signum() <= 0) {
            throw new IllegalArgumentException("Principal must be positive");
        }
        if (termMonths <= 0) {
            throw new IllegalArgumentException("Term must be positive");
        }

        BigDecimal annualRate = centralBankService.currentRates().policyRate().add(RISK_SPREAD);
        BigDecimal monthlyRate = annualRate.divide(MONTHS_PER_YEAR, MathContext.DECIMAL64);
        BigDecimal installmentAmount = installmentAmount(principal, monthlyRate, termMonths);
        LocalDate originationDate = clockService.state().simulatedTime().toLocalDate();

        LoanAccount loan = loanRepository.insertLoan(
                customerId, disbursementAccountId, principal, annualRate, termMonths, installmentAmount, originationDate);

        generateSchedule(loan.id(), principal, monthlyRate, installmentAmount, termMonths, originationDate);
        return loan;
    }

    /** Posts the disbursement: Debit LOAN_RECEIVABLE, Credit the disbursement account's CUSTOMER_LIABILITY. */
    @Transactional
    public LoanAccount disburse(long loanId) {
        LoanAccount loan = loanRepository.findById(loanId);
        long loanReceivableId = ledgerAccountService.createLoanReceivableAccount(loanId).id();
        long liabilityId = ledgerAccountService.findCustomerLiabilityAccount(loan.disbursementAccountId()).id();

        ledgerService.post(new JournalEntryRequest(
                "Disbursement of loan " + loanId,
                List.of(
                        new LedgerLineRequest(loanReceivableId, EntryType.DEBIT, loan.principal()),
                        new LedgerLineRequest(liabilityId, EntryType.CREDIT, loan.principal()))));

        events.publish(new LoanOriginatedEvent(
                loan.id(), loan.customerId(), loan.disbursementAccountId(), loan.principal(), loan.annualRate(), loan.termMonths()));
        return loan;
    }

    /** Originates and immediately disburses - what {@code POST /api/loans} calls. */
    @Transactional
    public LoanAccount originateAndDisburse(long customerId, long disbursementAccountId, BigDecimal principal, int termMonths) {
        LoanAccount loan = originate(customerId, disbursementAccountId, principal, termMonths);
        return disburse(loan.id());
    }

    /**
     * Applies a repayment of {@code amount} against the loan: a normal
     * installment payment, a partial early payoff (extra principal), or a
     * full early payoff, depending on how {@code amount} compares to what's
     * currently due. See the class-level docs for the term-reduction
     * prepayment model.
     */
    @Transactional
    public LoanPayment repay(long loanId, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }

        LoanAccount loan = loanRepository.lockForUpdate(loanId);
        if (loan.status() != LoanStatus.ACTIVE) {
            throw new IllegalStateException("Loan " + loanId + " is not active");
        }

        BigDecimal monthlyRate = loan.annualRate().divide(MONTHS_PER_YEAR, MathContext.DECIMAL64);
        BigDecimal outstandingPrincipal = loan.outstandingPrincipal();
        BigDecimal interestDue = outstandingPrincipal.multiply(monthlyRate).setScale(2, RoundingMode.HALF_UP);
        if (amount.compareTo(interestDue) < 0) {
            throw new IllegalArgumentException("Amount must cover at least the interest due (" + interestDue + ")");
        }

        BigDecimal regularPrincipalDue = loan.installmentAmount().subtract(interestDue).min(outstandingPrincipal);
        if (regularPrincipalDue.signum() < 0) {
            regularPrincipalDue = BigDecimal.ZERO;
        }
        BigDecimal fullPayoffAmount = interestDue.add(outstandingPrincipal);

        LoanPaymentType type;
        BigDecimal principalPortion;
        BigDecimal amountCharged;
        if (amount.compareTo(fullPayoffAmount) >= 0) {
            type = LoanPaymentType.EARLY_PAYOFF;
            principalPortion = outstandingPrincipal;
            amountCharged = fullPayoffAmount;
        } else if (amount.compareTo(interestDue.add(regularPrincipalDue)) <= 0) {
            type = LoanPaymentType.NORMAL;
            principalPortion = amount.subtract(interestDue);
            amountCharged = amount;
        } else {
            type = LoanPaymentType.EARLY_PARTIAL;
            principalPortion = amount.subtract(interestDue);
            amountCharged = amount;
        }

        BigDecimal newOutstandingPrincipal = outstandingPrincipal.subtract(principalPortion);
        boolean paidOff = type == LoanPaymentType.EARLY_PAYOFF || newOutstandingPrincipal.signum() <= 0;
        if (paidOff) {
            newOutstandingPrincipal = BigDecimal.ZERO;
        }
        int nextInstallmentNumber = paidOff ? loan.nextInstallmentNumber() : loan.nextInstallmentNumber() + 1;
        LoanStatus newStatus = paidOff ? LoanStatus.PAID_OFF : LoanStatus.ACTIVE;

        long liabilityId = ledgerAccountService.findCustomerLiabilityAccount(loan.disbursementAccountId()).id();
        long loanReceivableId = ledgerAccountService.findLoanReceivableAccount(loanId).id();
        long interestIncomeId = ledgerAccountService.getSingleton(LedgerAccountType.INTEREST_INCOME).id();

        Long journalEntryId = null;
        if (amountCharged.signum() > 0) {
            journalEntryId = ledgerService.post(new JournalEntryRequest(
                    "Repayment for loan " + loanId,
                    List.of(
                            new LedgerLineRequest(liabilityId, EntryType.DEBIT, amountCharged),
                            new LedgerLineRequest(loanReceivableId, EntryType.CREDIT, principalPortion),
                            new LedgerLineRequest(interestIncomeId, EntryType.CREDIT, interestDue))));
        }

        loanRepository.updateAfterPayment(loanId, newOutstandingPrincipal, nextInstallmentNumber, newStatus);
        LocalDate paymentDate = clockService.state().simulatedTime().toLocalDate();
        LoanPayment payment = loanRepository.insertPayment(
                loanId, paymentDate, type, amountCharged, interestDue, principalPortion, newOutstandingPrincipal, journalEntryId);

        events.publish(new LoanRepaidEvent(loanId, type, amountCharged, principalPortion, interestDue, newOutstandingPrincipal, paidOff));
        return payment;
    }

    public LoanAccount findById(long loanId) {
        return loanRepository.findById(loanId);
    }

    public LoanDetail findDetailById(long loanId) {
        LoanAccount loan = loanRepository.findById(loanId);
        return new LoanDetail(loan, loanRepository.findInstallmentsByLoanId(loanId), loanRepository.findPaymentsByLoanId(loanId));
    }

    public List<LoanAccount> findByAccountId(long accountId) {
        return loanRepository.findByDisbursementAccountId(accountId);
    }

    private void generateSchedule(
            long loanId, BigDecimal principal, BigDecimal monthlyRate, BigDecimal installmentAmount,
            int termMonths, LocalDate originationDate) {
        BigDecimal opening = principal;
        LocalDate dueDate = originationDate;
        for (int i = 1; i <= termMonths; i++) {
            dueDate = dueDate.plusMonths(1);
            BigDecimal interest = opening.multiply(monthlyRate).setScale(2, RoundingMode.HALF_UP);
            BigDecimal principalPortion = i == termMonths ? opening : installmentAmount.subtract(interest);
            BigDecimal closing = opening.subtract(principalPortion);
            loanRepository.insertInstallment(loanId, i, dueDate, opening, interest, principalPortion, closing);
            opening = closing;
        }
    }

    private static BigDecimal installmentAmount(BigDecimal principal, BigDecimal monthlyRate, int termMonths) {
        if (monthlyRate.signum() == 0) {
            return principal.divide(BigDecimal.valueOf(termMonths), 2, RoundingMode.HALF_UP);
        }
        BigDecimal onePlusR = BigDecimal.ONE.add(monthlyRate);
        BigDecimal factor = onePlusR.pow(termMonths, MathContext.DECIMAL64);
        BigDecimal numerator = principal.multiply(monthlyRate).multiply(factor);
        BigDecimal denominator = factor.subtract(BigDecimal.ONE);
        return numerator.divide(denominator, 2, RoundingMode.HALF_UP);
    }
}
