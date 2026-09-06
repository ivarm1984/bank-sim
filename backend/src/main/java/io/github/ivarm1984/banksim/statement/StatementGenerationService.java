package io.github.ivarm1984.banksim.statement;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.ivarm1984.banksim.account.AccountService;

/**
 * Generates one day's statement for one account. Kept as a separate bean
 * from {@link StatementGenerationScheduler} (rather than one self-invoked
 * method) so {@link #generateForAccount} runs through Spring's transactional
 * proxy - one transaction per account, not one giant transaction for the
 * whole day's batch.
 */
@Service
public class StatementGenerationService {

    private final AccountService accountService;
    private final StatementRepository statementRepository;

    public StatementGenerationService(AccountService accountService, StatementRepository statementRepository) {
        this.accountService = accountService;
        this.statementRepository = statementRepository;
    }

    /**
     * Closing balance is the account's current balance (after the day's
     * interest accrual has already posted). Opening balance chains from the
     * account's most recent prior statement's closing balance; an account
     * with no prior statement (its very first one) has no earlier balance to
     * chain from, so opening defaults to the same closing balance.
     */
    @Transactional
    public Statement generateForAccount(long accountId, LocalDate date) {
        BigDecimal closingBalance = accountService.balanceOf(accountId);
        BigDecimal openingBalance = statementRepository.findMostRecent(accountId)
                .map(Statement::closingBalance)
                .orElse(closingBalance);
        return statementRepository.insert(accountId, date, openingBalance, closingBalance);
    }

    public List<Statement> findByAccountId(long accountId) {
        return statementRepository.findByAccountId(accountId);
    }
}
