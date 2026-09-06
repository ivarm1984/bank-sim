package io.github.ivarm1984.banksim.loan;

import java.math.BigDecimal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LoanController {

    private final LoanService loanService;

    public LoanController(LoanService loanService) {
        this.loanService = loanService;
    }

    @PostMapping("/api/loans")
    public ResponseEntity<LoanAccount> originate(@Valid @RequestBody OriginateLoanRequest request) {
        LoanAccount created = loanService.originateAndDisburse(
                request.customerId(), request.disbursementAccountId(), request.principal(), request.termMonths());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/api/loans/{id}")
    public LoanDetail findById(@PathVariable long id) {
        return loanService.findDetailById(id);
    }

    @PostMapping("/api/loans/{id}/repay")
    public LoanPayment repay(@PathVariable long id, @Valid @RequestBody RepayLoanRequest request) {
        return loanService.repay(id, request.amount());
    }

    public record OriginateLoanRequest(
            @NotNull Long customerId, @NotNull Long disbursementAccountId, @NotNull @Positive BigDecimal principal,
            @NotNull @Positive Integer termMonths) {
    }

    public record RepayLoanRequest(@NotNull @Positive BigDecimal amount) {
    }
}
