package io.github.ivarm1984.banksim.interest;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/accounts/{accountId}/interest-accruals")
public class InterestAccrualController {

    private final InterestAccrualService interestAccrualService;

    public InterestAccrualController(InterestAccrualService interestAccrualService) {
        this.interestAccrualService = interestAccrualService;
    }

    @GetMapping
    public List<InterestAccrual> findByAccountId(@PathVariable long accountId) {
        return interestAccrualService.findByAccountId(accountId);
    }
}
