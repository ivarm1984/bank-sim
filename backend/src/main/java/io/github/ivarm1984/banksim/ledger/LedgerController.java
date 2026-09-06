package io.github.ivarm1984.banksim.ledger;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ledger")
public class LedgerController {

    private final LedgerAccountService ledgerAccountService;
    private final LedgerReconciliationService ledgerReconciliationService;

    public LedgerController(LedgerAccountService ledgerAccountService, LedgerReconciliationService ledgerReconciliationService) {
        this.ledgerAccountService = ledgerAccountService;
        this.ledgerReconciliationService = ledgerReconciliationService;
    }

    @GetMapping("/accounts")
    public List<LedgerAccountBalance> accounts() {
        return ledgerAccountService.findAllWithBalances();
    }

    @GetMapping("/trial-balance")
    public LedgerReconciliationService.TrialBalance trialBalance() {
        return ledgerReconciliationService.trialBalance();
    }
}
