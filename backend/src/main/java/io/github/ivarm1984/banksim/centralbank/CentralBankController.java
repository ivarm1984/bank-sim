package io.github.ivarm1984.banksim.centralbank;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/central-bank")
public class CentralBankController {

    private final CentralBankService centralBankService;

    public CentralBankController(CentralBankService centralBankService) {
        this.centralBankService = centralBankService;
    }

    @GetMapping("/rates")
    public CentralBankRates rates() {
        return centralBankService.currentRates();
    }
}
