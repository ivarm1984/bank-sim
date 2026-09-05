package io.github.ivarm1984.banksim.account;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    public ResponseEntity<Account> open(@Valid @RequestBody OpenAccountRequest request) {
        Account created = accountService.open(request.customerId(), request.accountType());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping
    public List<Account> findAll() {
        return accountService.findAll();
    }

    @GetMapping("/{id}")
    public Account findById(@PathVariable long id) {
        return accountService.findById(id);
    }

    @GetMapping("/{id}/balance")
    public Map<String, BigDecimal> balance(@PathVariable long id) {
        return Map.of("balance", accountService.balanceOf(id));
    }

    public record OpenAccountRequest(@NotNull Long customerId, @NotBlank String accountType) {
    }
}
