package io.github.ivarm1984.banksim.treasury;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import io.github.ivarm1984.banksim.ledger.EntryType;
import io.github.ivarm1984.banksim.ledger.JournalEntryRequest;
import io.github.ivarm1984.banksim.ledger.LedgerAccount;
import io.github.ivarm1984.banksim.ledger.LedgerAccountService;
import io.github.ivarm1984.banksim.ledger.LedgerAccountType;
import io.github.ivarm1984.banksim.ledger.LedgerLineRequest;
import io.github.ivarm1984.banksim.ledger.LedgerService;

/**
 * Posts the bank's initial paid-in capital on first startup only - skipped
 * once the BANK_CAPITAL ledger account already has lines posted against it,
 * so re-running {@code bootRun} doesn't duplicate the seed entry.
 */
@Component
public class TreasuryCapitalSeeder implements ApplicationRunner {

    private static final BigDecimal SEED_CAPITAL = new BigDecimal("1000000.00");

    private final LedgerAccountService ledgerAccountService;
    private final LedgerService ledgerService;

    public TreasuryCapitalSeeder(LedgerAccountService ledgerAccountService, LedgerService ledgerService) {
        this.ledgerAccountService = ledgerAccountService;
        this.ledgerService = ledgerService;
    }

    @Override
    public void run(ApplicationArguments args) {
        LedgerAccount bankCapital = ledgerAccountService.getSingleton(LedgerAccountType.BANK_CAPITAL);
        if (ledgerAccountService.hasAnyLedgerLines(bankCapital.id())) {
            return;
        }

        LedgerAccount reserves = ledgerAccountService.getSingleton(LedgerAccountType.CENTRAL_BANK_RESERVES);
        ledgerService.post(new JournalEntryRequest(
                "Initial bank capital seed",
                List.of(
                        new LedgerLineRequest(reserves.id(), EntryType.DEBIT, SEED_CAPITAL),
                        new LedgerLineRequest(bankCapital.id(), EntryType.CREDIT, SEED_CAPITAL))));
    }
}
