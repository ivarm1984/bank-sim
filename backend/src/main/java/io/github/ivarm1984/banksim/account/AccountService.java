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

    /**
     * Opens many accounts and their backing CUSTOMER_LIABILITY ledger
     * accounts in two multi-row INSERTs, instead of one round trip per
     * account - for bulk seeding (see {@code DataSeeder}), where the
     * per-account path would mean tens of thousands of round trips.
     */
    @Transactional
    public List<Account> openBatch(List<AccountOpenRequest> requests) {
        List<Account> accounts = accountRepository.insertBatch(requests);
        ledgerAccountService.createCustomerLiabilityAccountsBatch(accounts.stream().map(Account::id).toList());
        return accounts;
    }

    public Account findById(long id) {
        return accountRepository.findById(id);
    }

    public List<Account> findAll() {
        return accountRepository.findAll();
    }

    public List<Account> findPage(int limit, int offset) {
        return accountRepository.findPage(limit, offset);
    }

    public BigDecimal balanceOf(long id) {
        return accountRepository.findById(id).currentBalance();
    }
}
