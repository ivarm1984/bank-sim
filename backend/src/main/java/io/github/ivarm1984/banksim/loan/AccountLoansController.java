package io.github.ivarm1984.banksim.loan;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/accounts/{accountId}/loans")
public class AccountLoansController {

    private final LoanService loanService;

    public AccountLoansController(LoanService loanService) {
        this.loanService = loanService;
    }

    @GetMapping
    public List<LoanAccount> findByAccountId(@PathVariable long accountId) {
        return loanService.findByAccountId(accountId);
    }
}
