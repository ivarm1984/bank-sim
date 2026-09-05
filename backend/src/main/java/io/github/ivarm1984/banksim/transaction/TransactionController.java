package io.github.ivarm1984.banksim.transaction;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @PostMapping("/deposit")
    public ResponseEntity<Transaction> deposit(@Valid @RequestBody AccountAmountRequest request) {
        Transaction created = transactionService.deposit(request.accountId(), request.amount());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PostMapping("/withdrawal")
    public ResponseEntity<Transaction> withdraw(@Valid @RequestBody AccountAmountRequest request) {
        Transaction created = transactionService.withdraw(request.accountId(), request.amount());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PostMapping("/transfer")
    public ResponseEntity<Transaction> transfer(@Valid @RequestBody TransferRequest request) {
        Transaction created = transactionService.transfer(request.fromAccountId(), request.toAccountId(), request.amount());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping
    public List<Transaction> findAll() {
        return transactionService.findAll();
    }

    public record AccountAmountRequest(@NotNull Long accountId, @NotNull BigDecimal amount) {
    }

    public record TransferRequest(@NotNull Long fromAccountId, @NotNull Long toAccountId, @NotNull BigDecimal amount) {
    }
}
