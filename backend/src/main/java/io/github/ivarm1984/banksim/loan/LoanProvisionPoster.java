package io.github.ivarm1984.banksim.loan;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.stereotype.Component;

import io.github.ivarm1984.banksim.ledger.EntryType;
import io.github.ivarm1984.banksim.ledger.JournalEntryRequest;
import io.github.ivarm1984.banksim.ledger.LedgerAccountService;
import io.github.ivarm1984.banksim.ledger.LedgerAccountType;
import io.github.ivarm1984.banksim.ledger.LedgerLineRequest;
import io.github.ivarm1984.banksim.ledger.LedgerService;

/**
 * Posts a change in one loan's loan-loss provision to the singleton
 * LOAN_LOSS_PROVISION / PROVISION_EXPENSE accounts - a positive delta is a
 * charge (Debit PROVISION_EXPENSE / Credit LOAN_LOSS_PROVISION), a negative
 * one a release back to profit (the reverse). Shared by
 * {@link LoanStagingService} (stage changes, macro remeasurement) and
 * {@code LoanService} (day-one allowance at disbursement, remeasurement after
 * each repayment, full release on payoff).
 */
@Component
class LoanProvisionPoster {

    private final LedgerService ledgerService;
    private final LedgerAccountService ledgerAccountService;

    LoanProvisionPoster(LedgerService ledgerService, LedgerAccountService ledgerAccountService) {
        this.ledgerService = ledgerService;
        this.ledgerAccountService = ledgerAccountService;
    }

    /** @return the journal entry id, or null when {@code delta} is zero and nothing was posted. */
    Long post(BigDecimal delta, String description) {
        if (delta.signum() == 0) {
            return null;
        }
        long expenseId = ledgerAccountService.getSingleton(LedgerAccountType.PROVISION_EXPENSE).id();
        long provisionId = ledgerAccountService.getSingleton(LedgerAccountType.LOAN_LOSS_PROVISION).id();
        BigDecimal amount = delta.abs();
        EntryType expenseSide = delta.signum() > 0 ? EntryType.DEBIT : EntryType.CREDIT;
        EntryType provisionSide = delta.signum() > 0 ? EntryType.CREDIT : EntryType.DEBIT;
        return ledgerService.post(new JournalEntryRequest(
                description,
                List.of(
                        new LedgerLineRequest(expenseId, expenseSide, amount),
                        new LedgerLineRequest(provisionId, provisionSide, amount))));
    }
}
