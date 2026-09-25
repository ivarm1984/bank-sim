package io.github.ivarm1984.banksim.loan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.github.ivarm1984.banksim.PostgresIntegrationTest;
import io.github.ivarm1984.banksim.TestCustomers;
import io.github.ivarm1984.banksim.account.Account;
import io.github.ivarm1984.banksim.account.AccountService;
import io.github.ivarm1984.banksim.account.AccountType;
import io.github.ivarm1984.banksim.customer.Customer;
import io.github.ivarm1984.banksim.customer.CustomerService;
import io.github.ivarm1984.banksim.ledger.LedgerAccountBalance;
import io.github.ivarm1984.banksim.ledger.LedgerAccountService;
import io.github.ivarm1984.banksim.ledger.LedgerReconciliationService;

/**
 * Write-offs against real loans. Like {@link LoanStagingServiceTest}, these
 * stage and write off only their own loan (the per-loan
 * {@code evaluateLoan}/{@code writeOffIfDue}), since the daily passes would
 * touch every loan in the shared Testcontainers DB.
 */
class LoanWriteOffServiceTest extends PostgresIntegrationTest {

    @Autowired
    private LoanService loanService;
    @Autowired
    private LoanStagingService stagingService;
    @Autowired
    private LoanWriteOffService writeOffService;
    @Autowired
    private LedgerAccountService ledgerAccountService;
    @Autowired
    private AccountService accountService;
    @Autowired
    private CustomerService customerService;
    @Autowired
    private LedgerReconciliationService reconciliationService;

    private LoanAccount originate(LoanType type, BigDecimal principal, int termMonths) {
        Customer customer = customerService.create(TestCustomers.affluent("Test Write-off Borrower"));
        Account account = accountService.open(customer.id(), AccountType.CHECKING);
        return loanService.originateAndDisburse(account.customerId(), account.id(), type, principal, termMonths);
    }

    /** Stages the loan into default at 90 days past its first due date, and returns that default date. */
    private LocalDate defaultLoan(LoanAccount loan) {
        LocalDate defaultDate = loan.originationDate().plusMonths(1).plusDays(90);
        stagingService.evaluateLoan(loan.id(), defaultDate, false);
        assertThat(loanService.findById(loan.id()).phase()).isEqualTo(LoanPhase.NON_PERFORMING);
        return defaultDate;
    }

    private BigDecimal receivableBalance(long loanId) {
        LedgerAccountBalance balance = ledgerAccountService.findAllWithBalances().stream()
                .filter(b -> Long.valueOf(loanId).equals(b.loanId()))
                .findFirst()
                .orElseThrow();
        return balance.totalDebits().subtract(balance.totalCredits());
    }

    @Test
    void anUnsecuredLoanTwelveMonthsInDefaultIsWrittenOffAgainstItsAllowanceWithTheRecoveryInCash() {
        LoanAccount loan = originate(LoanType.BUSINESS, new BigDecimal("100000.00"), 60);
        LocalDate defaultDate = defaultLoan(loan);

        writeOffService.writeOffIfDue(loan.id(), defaultDate.plusMonths(12).minusDays(1));
        assertThat(loanService.findById(loan.id()).status()).isEqualTo(LoanStatus.ACTIVE);

        LocalDate writeOffDate = defaultDate.plusMonths(12);
        writeOffService.writeOffIfDue(loan.id(), writeOffDate);

        LoanAccount writtenOff = loanService.findById(loan.id());
        assertThat(writtenOff.status()).isEqualTo(LoanStatus.WRITTEN_OFF);
        assertThat(writtenOff.outstandingPrincipal()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(writtenOff.provisionAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(receivableBalance(loan.id())).isEqualByComparingTo(BigDecimal.ZERO);

        LoanWriteOff writeOff = loanService.findDetailById(loan.id()).writeOff();
        assertThat(writeOff.writeOffDate()).isEqualTo(writeOffDate);
        assertThat(writeOff.defaultedSince()).isEqualTo(defaultDate);
        assertThat(writeOff.outstandingPrincipal()).isEqualByComparingTo("100000.00");
        // Business LGD 45%: 55% recovered, the other 45% is exactly the Stage 3 allowance.
        assertThat(writeOff.recoveryAmount()).isEqualByComparingTo("55000.00");
        assertThat(writeOff.writtenOffAmount()).isEqualByComparingTo("45000.00");
        assertThat(writeOff.allowanceUsed()).isEqualByComparingTo("45000.00");
        assertThat(writeOff.journalEntryId()).isNotNull();
        assertThat(reconciliationService.trialBalance().isBalanced()).isTrue();

        assertThatThrownBy(() -> loanService.repay(loan.id(), loan.installmentAmount()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aMortgageIsOnlyWrittenOffAfterTwentyFourMonthsInDefault() {
        LoanAccount loan = originate(LoanType.MORTGAGE, new BigDecimal("200000.00"), 240);
        LocalDate defaultDate = defaultLoan(loan);

        writeOffService.writeOffIfDue(loan.id(), defaultDate.plusMonths(12));
        assertThat(loanService.findById(loan.id()).status()).isEqualTo(LoanStatus.ACTIVE);

        writeOffService.writeOffIfDue(loan.id(), defaultDate.plusMonths(24));
        assertThat(loanService.findById(loan.id()).status()).isEqualTo(LoanStatus.WRITTEN_OFF);
        LoanWriteOff writeOff = loanService.findDetailById(loan.id()).writeOff();
        assertThat(writeOff.recoveryAmount()).isEqualByComparingTo("170000.00");
        assertThat(writeOff.writtenOffAmount()).isEqualByComparingTo("30000.00");
        assertThat(reconciliationService.trialBalance().isBalanced()).isTrue();
    }

    @Test
    void aDefaultedLoanOnCureProbationIsNotWrittenOff() {
        LoanAccount loan = originate(LoanType.CONSUMER, new BigDecimal("10000.00"), 36);
        LocalDate defaultDate = defaultLoan(loan);

        // Catch up on arrears and pay well ahead, then let staging start the probation.
        for (int i = 0; i < 10; i++) {
            loanService.repay(loan.id(), loan.installmentAmount());
        }
        stagingService.evaluateLoan(loan.id(), defaultDate.plusDays(1), false);
        assertThat(loanService.findById(loan.id()).probationStartDate()).isNotNull();

        writeOffService.writeOffIfDue(loan.id(), defaultDate.plusMonths(13));
        assertThat(loanService.findById(loan.id()).status()).isEqualTo(LoanStatus.ACTIVE);
        assertThat(loanService.findDetailById(loan.id()).writeOff()).isNull();
    }
}
