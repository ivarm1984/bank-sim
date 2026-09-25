package io.github.ivarm1984.banksim.loan;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.ivarm1984.banksim.centralbank.CentralBankService;
import io.github.ivarm1984.banksim.clock.ClockService;
import io.github.ivarm1984.banksim.customer.Customer;
import io.github.ivarm1984.banksim.customer.CustomerService;
import io.github.ivarm1984.banksim.event.DomainEventPublisher;
import io.github.ivarm1984.banksim.eventinjector.RecessionShockService;
import io.github.ivarm1984.banksim.ledger.EntryType;
import io.github.ivarm1984.banksim.ledger.JournalEntryRequest;
import io.github.ivarm1984.banksim.ledger.LedgerAccountService;
import io.github.ivarm1984.banksim.ledger.LedgerAccountType;
import io.github.ivarm1984.banksim.ledger.LedgerLineRequest;
import io.github.ivarm1984.banksim.ledger.LedgerService;
import io.github.ivarm1984.banksim.policy.PolicyLevers;
import io.github.ivarm1984.banksim.policy.PolicyLeversSnapshot;
import io.github.ivarm1984.banksim.treasury.TreasuryService;

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

    private static final BigDecimal MONTHS_PER_YEAR = new BigDecimal("12");

    private final LoanRepository loanRepository;
    private final LedgerService ledgerService;
    private final LedgerAccountService ledgerAccountService;
    private final CentralBankService centralBankService;
    private final ClockService clockService;
    private final DomainEventPublisher events;
    private final TreasuryService treasuryService;
    private final PolicyLevers policyLevers;
    private final LoanProvisionPoster provisionPoster;
    private final RecessionShockService recessionShockService;
    private final CustomerService customerService;

    public LoanService(
            LoanRepository loanRepository, LedgerService ledgerService, LedgerAccountService ledgerAccountService,
            CentralBankService centralBankService, ClockService clockService, DomainEventPublisher events,
            TreasuryService treasuryService, PolicyLevers policyLevers, LoanProvisionPoster provisionPoster,
            RecessionShockService recessionShockService, CustomerService customerService) {
        this.loanRepository = loanRepository;
        this.ledgerService = ledgerService;
        this.ledgerAccountService = ledgerAccountService;
        this.centralBankService = centralBankService;
        this.clockService = clockService;
        this.events = events;
        this.treasuryService = treasuryService;
        this.policyLevers = policyLevers;
        this.provisionPoster = provisionPoster;
        this.recessionShockService = recessionShockService;
        this.customerService = customerService;
    }

    /**
     * Underwrites and approves a loan: rates the borrower (PD from product,
     * credit grade and debt service to income - see {@link CreditRisk}),
     * declines it if unaffordable or riskier than the CEO's approval cutoff,
     * prices it (see {@link RiskBasedPricing}), then persists the loan, its
     * pricing and the full amortization schedule. No money moves yet - see
     * {@link #disburse(long)}.
     *
     * <p>Affordability is assessed at the rate before the credit-risk
     * premium, since that premium itself depends on the DSTI being assessed.
     * Both the approval cutoff and the price use the recession-adjusted PD,
     * so credit tightens and gets dearer while a recession is on; the loan
     * stores the baseline PD, which its ECL stresses again day by day.
     *
     * @throws LoanDeclinedException if underwriting turns the borrower down
     */
    @Transactional
    public LoanAccount originate(long customerId, long disbursementAccountId, LoanType loanType, BigDecimal principal, int termMonths) {
        if (principal == null || principal.signum() <= 0) {
            throw new IllegalArgumentException("Principal must be positive");
        }
        if (termMonths <= 0) {
            throw new IllegalArgumentException("Term must be positive");
        }
        if (treasuryService.isLoanOriginationThrottled()) {
            throw new IllegalStateException(
                    "Loan origination is currently throttled: treasury's capital/funding ratio is below its regulatory minimum");
        }
        if (loanType == LoanType.MORTGAGE && hasMortgage(disbursementAccountId)) {
            throw new IllegalStateException("Account " + disbursementAccountId + " already has a mortgage - only one is allowed");
        }

        PolicyLeversSnapshot levers = policyLevers.state();
        BigDecimal spreadAdjustment = switch (loanType) {
            case MORTGAGE -> levers.mortgageSpreadAdjustment();
            case CONSUMER -> levers.consumerSpreadAdjustment();
            case BUSINESS -> levers.businessSpreadAdjustment();
        };
        BigDecimal policyRate = centralBankService.currentRates().policyRate();
        BigDecimal capitalRequirement = TreasuryService.OVERALL_CAPITAL_REQUIREMENT.add(levers.targetCapitalBuffer());

        Customer customer = customerService.findById(customerId);
        BigDecimal affordabilityRate = RiskBasedPricing.rateBeforeCreditRisk(loanType, policyRate, capitalRequirement, spreadAdjustment);
        BigDecimal debtService = loanRepository.activeInstallmentsByCustomerId(customerId)
                .add(installmentAmount(principal, monthlyRate(affordabilityRate), termMonths));
        BigDecimal debtServiceToIncome = debtService.divide(customer.monthlyIncome(), 6, RoundingMode.HALF_UP);
        if (debtServiceToIncome.compareTo(CreditRisk.MAX_DEBT_SERVICE_TO_INCOME) > 0) {
            throw new LoanDeclinedException("Declined: debt service would be " + percent(debtServiceToIncome)
                    + " of income, above the " + percent(CreditRisk.MAX_DEBT_SERVICE_TO_INCOME) + " affordability limit");
        }

        BigDecimal baselinePd = CreditRisk.borrowerDefaultProbability(loanType, customer.creditGrade(), debtServiceToIncome);
        BigDecimal pricingPd = CreditRisk.twelveMonthDefaultProbability(baselinePd, recessionActiveToday());
        BigDecimal maxPd = CreditRisk.maxApprovalDefaultProbability(levers.underwritingLooseness());
        if (pricingPd.compareTo(maxPd) > 0) {
            throw new LoanDeclinedException("Declined: PD " + percent(pricingPd) + " (grade " + customer.creditGrade()
                    + ", DSTI " + percent(debtServiceToIncome) + ") is above the " + percent(maxPd) + " approval cutoff");
        }

        LoanPricing pricing = RiskBasedPricing.price(
                loanType, customer.creditGrade(), customer.monthlyIncome(), debtServiceToIncome, pricingPd, policyRate,
                capitalRequirement, spreadAdjustment);
        BigDecimal monthlyRate = monthlyRate(pricing.annualRate());
        BigDecimal installmentAmount = installmentAmount(principal, monthlyRate, termMonths);
        LocalDate originationDate = clockService.state().simulatedTime().toLocalDate();

        LoanAccount loan = loanRepository.insertLoan(
                customerId, disbursementAccountId, loanType, baselinePd, principal, pricing.annualRate(), termMonths,
                installmentAmount, originationDate);
        loanRepository.insertPricing(loan.id(), pricing);

        generateSchedule(loan.id(), principal, monthlyRate, installmentAmount, termMonths, originationDate);
        return loan;
    }

    private static BigDecimal monthlyRate(BigDecimal annualRate) {
        return annualRate.divide(MONTHS_PER_YEAR, MathContext.DECIMAL64);
    }

    private static String percent(BigDecimal fraction) {
        return fraction.movePointRight(2).setScale(1, RoundingMode.HALF_UP) + "%";
    }

    private boolean hasMortgage(long disbursementAccountId) {
        return loanRepository.findByDisbursementAccountId(disbursementAccountId).stream()
                .anyMatch(loan -> loan.loanType() == LoanType.MORTGAGE);
    }

    /**
     * Posts the disbursement (Debit LOAN_RECEIVABLE, Credit the disbursement
     * account's CUSTOMER_LIABILITY) and the day-one Stage 1 loss allowance -
     * under IFRS 9 every performing loan carries its 12-month ECL from the
     * moment it's recognised.
     */
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

        BigDecimal initialProvision = CreditRisk.expectedCreditLoss(
                loan.loanType(), loan.probabilityOfDefault(), LoanPhase.PERFORMING, loan.principal(), loan.termMonths(), recessionActiveToday());
        provisionPoster.post(initialProvision, "Loan " + loanId + " initial Stage 1 ECL");
        loanRepository.updateProvision(loanId, initialProvision);

        LoanPricing pricing = loanRepository.findPricingByLoanId(loanId).orElse(null);
        events.publish(new LoanOriginatedEvent(
                loan.id(), loan.customerId(), loan.disbursementAccountId(), loan.loanType(), loan.principal(),
                loan.annualRate(), loan.termMonths(), pricing == null ? null : pricing.creditGrade(), loan.probabilityOfDefault()));
        return loan;
    }

    /** Originates and immediately disburses - what {@code POST /api/loans} calls. */
    @Transactional
    public LoanAccount originateAndDisburse(
            long customerId, long disbursementAccountId, LoanType loanType, BigDecimal principal, int termMonths) {
        LoanAccount loan = originate(customerId, disbursementAccountId, loanType, principal, termMonths);
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

        BigDecimal monthlyRate = monthlyRate(loan.annualRate());
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

        // IFRS 9 5.4.1(b): a credit-impaired (Stage 3) loan's interest revenue is
        // recognised on its net carrying amount. The rest of what the borrower
        // pays is a recovery against the loss allowance - credited to
        // PROVISION_EXPENSE (an impairment gain) rather than interest income.
        BigDecimal recognisedInterest = recognisedInterest(loan, interestDue);
        BigDecimal impairmentGain = interestDue.subtract(recognisedInterest);

        Long journalEntryId = null;
        if (amountCharged.signum() > 0) {
            List<LedgerLineRequest> lines = new ArrayList<>(List.of(
                    new LedgerLineRequest(liabilityId, EntryType.DEBIT, amountCharged),
                    new LedgerLineRequest(loanReceivableId, EntryType.CREDIT, principalPortion),
                    new LedgerLineRequest(interestIncomeId, EntryType.CREDIT, recognisedInterest)));
            if (impairmentGain.signum() > 0) {
                long provisionExpenseId = ledgerAccountService.getSingleton(LedgerAccountType.PROVISION_EXPENSE).id();
                lines.add(new LedgerLineRequest(provisionExpenseId, EntryType.CREDIT, impairmentGain));
            }
            journalEntryId = ledgerService.post(new JournalEntryRequest("Repayment for loan " + loanId, lines));
        }

        loanRepository.updateAfterPayment(loanId, newOutstandingPrincipal, nextInstallmentNumber, newStatus);
        remeasureProvision(loan, newOutstandingPrincipal, nextInstallmentNumber, paidOff);
        LocalDate paymentDate = clockService.state().simulatedTime().toLocalDate();
        LoanPayment payment = loanRepository.insertPayment(
                loanId, paymentDate, type, amountCharged, interestDue, principalPortion, newOutstandingPrincipal, journalEntryId);

        events.publish(new LoanRepaidEvent(loanId, type, amountCharged, principalPortion, interestDue, newOutstandingPrincipal, paidOff));
        return payment;
    }

    private static BigDecimal recognisedInterest(LoanAccount loan, BigDecimal interestDue) {
        if (loan.phase() != LoanPhase.NON_PERFORMING || loan.outstandingPrincipal().signum() <= 0) {
            return interestDue;
        }
        BigDecimal netCarryingShare = loan.outstandingPrincipal().subtract(loan.provisionAmount())
                .divide(loan.outstandingPrincipal(), MathContext.DECIMAL64)
                .max(BigDecimal.ZERO);
        return interestDue.multiply(netCarryingShare).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Keeps the loss allowance in step with the exposure it covers: ECL is
     * remeasured on the *new* outstanding principal and remaining term after
     * every repayment (never leaving the contra-asset larger than the
     * receivable), and a payoff releases whatever is left in full.
     */
    private void remeasureProvision(LoanAccount loan, BigDecimal newOutstandingPrincipal, int nextInstallmentNumber, boolean paidOff) {
        int remainingMonths = Math.max(1, loan.termMonths() - (nextInstallmentNumber - 1));
        BigDecimal newProvision = paidOff
                ? BigDecimal.ZERO
                : CreditRisk.expectedCreditLoss(
                        loan.loanType(), loan.probabilityOfDefault(), loan.phase(), newOutstandingPrincipal, remainingMonths, recessionActiveToday());
        BigDecimal delta = newProvision.subtract(loan.provisionAmount());
        if (delta.signum() == 0) {
            return;
        }
        provisionPoster.post(delta, "Loan " + loan.id() + " provision remeasured after repayment"
                + (paidOff ? " (paid off - released in full)" : ""));
        loanRepository.updateProvision(loan.id(), newProvision);
    }

    private boolean recessionActiveToday() {
        return recessionShockService.isActive(clockService.state().simulatedTime().toLocalDate());
    }

    public LoanAccount findById(long loanId) {
        return loanRepository.findById(loanId);
    }

    public LoanDetail findDetailById(long loanId) {
        LoanAccount loan = loanRepository.findById(loanId);
        return new LoanDetail(
                loan, loanRepository.findInstallmentsByLoanId(loanId), loanRepository.findPaymentsByLoanId(loanId),
                loanRepository.findPhaseHistoryByLoanId(loanId), loanRepository.findWriteOffByLoanId(loanId).orElse(null),
                loanRepository.findPricingByLoanId(loanId).orElse(null));
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
