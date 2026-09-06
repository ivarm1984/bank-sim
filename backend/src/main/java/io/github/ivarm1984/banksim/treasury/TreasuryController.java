package io.github.ivarm1984.banksim.treasury;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/treasury")
public class TreasuryController {

    private final TreasuryService treasuryService;

    public TreasuryController(TreasuryService treasuryService) {
        this.treasuryService = treasuryService;
    }

    @GetMapping("/ratios")
    public TreasuryRatioSnapshot ratios() {
        return treasuryService.latest();
    }

    @GetMapping("/loan-origination-status")
    public LoanOriginationStatus loanOriginationStatus() {
        return new LoanOriginationStatus(treasuryService.isLoanOriginationThrottled());
    }

    public record LoanOriginationStatus(boolean throttled) {
    }
}
