package io.github.ivarm1984.banksim.account;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.ivarm1984.banksim.ledger.LedgerAccountService;

@Service
public class AccountService {

    private final AccountRepository accountRepository;
    private final LedgerAccountService ledgerAccountService;

    public AccountService(AccountRepository accountRepository, LedgerAccountService ledgerAccountService) {
        this.accountRepository = accountRepository;
        this.ledgerAccountService = ledgerAccountService;
    }

    /** Opens a new Account and its backing CUSTOMER_LIABILITY ledger account. */
    @Transactional
    public Account open(long customerId, AccountType accountType) {
        Account account = accountRepository.insert(customerId, accountType);
        ledgerAccountService.createCustomerLiabilityAccount(account.id());
        return account;
    }

    public Account findById(long id) {
        return accountRepository.findById(id);
    }

    public List<Account> findAll() {
        return accountRepository.findAll();
    }

    public BigDecimal balanceOf(long id) {
        return accountRepository.findById(id).currentBalance();
    }
}
