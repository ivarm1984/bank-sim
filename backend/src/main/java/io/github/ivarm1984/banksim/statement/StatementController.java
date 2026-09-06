package io.github.ivarm1984.banksim.statement;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/accounts/{accountId}/statements")
public class StatementController {

    private final StatementGenerationService statementGenerationService;

    public StatementController(StatementGenerationService statementGenerationService) {
        this.statementGenerationService = statementGenerationService;
    }

    @GetMapping
    public List<Statement> findByAccountId(@PathVariable long accountId) {
        return statementGenerationService.findByAccountId(accountId);
    }
}
