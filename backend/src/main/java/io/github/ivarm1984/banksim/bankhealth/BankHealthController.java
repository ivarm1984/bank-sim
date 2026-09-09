package io.github.ivarm1984.banksim.bankhealth;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/bank-health")
public class BankHealthController {

    private final BankHealthService bankHealthService;

    public BankHealthController(BankHealthService bankHealthService) {
        this.bankHealthService = bankHealthService;
    }

    @GetMapping("/status")
    public BankHealthSnapshot status() {
        return bankHealthService.latest();
    }
}
