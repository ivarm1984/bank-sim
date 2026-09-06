package io.github.ivarm1984.banksim.transaction;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.ivarm1984.banksim.event.DomainEventPublisher;
import io.github.ivarm1984.banksim.ledger.EntryType;
import io.github.ivarm1984.banksim.ledger.JournalEntryRequest;
import io.github.ivarm1984.banksim.ledger.LedgerAccountService;
import io.github.ivarm1984.banksim.ledger.LedgerAccountType;
import io.github.ivarm1984.banksim.ledger.LedgerLineRequest;
import io.github.ivarm1984.banksim.ledger.LedgerService;

@Service
public class TransactionService {

    private final LedgerService ledgerService;
    private final LedgerAccountService ledgerAccountService;
    private final TransactionRepository transactionRepository;
    private final DomainEventPublisher events;

    public TransactionService(
            LedgerService ledgerService,
            LedgerAccountService ledgerAccountService,
            TransactionRepository transactionRepository,
            DomainEventPublisher events) {
        this.ledgerService = ledgerService;
        this.ledgerAccountService = ledgerAccountService;
        this.transactionRepository = transactionRepository;
        this.events = events;
    }

    @Transactional
    public Transaction deposit(long accountId, BigDecimal amount) {
        validateAmount(amount);
        long bankCashId = ledgerAccountService.getSingleton(LedgerAccountType.BANK_CASH).id();
        long liabilityId = ledgerAccountService.findCustomerLiabilityAccount(accountId).id();

        long journalEntryId = ledgerService.post(new JournalEntryRequest(
                "Deposit to account " + accountId,
                List.of(
                        new LedgerLineRequest(bankCashId, EntryType.DEBIT, amount),
                        new LedgerLineRequest(liabilityId, EntryType.CREDIT, amount))));

        Transaction transaction = transactionRepository.insert(TransactionType.DEPOSIT, null, accountId, amount, journalEntryId);
        publishCompleted(transaction);
        return transaction;
    }

    @Transactional
    public Transaction withdraw(long accountId, BigDecimal amount) {
        validateAmount(amount);
        long bankCashId = ledgerAccountService.getSingleton(LedgerAccountType.BANK_CASH).id();
        long liabilityId = ledgerAccountService.findCustomerLiabilityAccount(accountId).id();

        long journalEntryId = ledgerService.post(new JournalEntryRequest(
                "Withdrawal from account " + accountId,
                List.of(
                        new LedgerLineRequest(liabilityId, EntryType.DEBIT, amount),
                        new LedgerLineRequest(bankCashId, EntryType.CREDIT, amount))));

        Transaction transaction = transactionRepository.insert(TransactionType.WITHDRAWAL, accountId, null, amount, journalEntryId);
        publishCompleted(transaction);
        return transaction;
    }

    @Transactional
    public Transaction transfer(long fromAccountId, long toAccountId, BigDecimal amount) {
        validateAmount(amount);
        if (fromAccountId == toAccountId) {
            throw new IllegalArgumentException("Cannot transfer to the same account");
        }
        long fromLiabilityId = ledgerAccountService.findCustomerLiabilityAccount(fromAccountId).id();
        long toLiabilityId = ledgerAccountService.findCustomerLiabilityAccount(toAccountId).id();

        long journalEntryId = ledgerService.post(new JournalEntryRequest(
                "Transfer from account " + fromAccountId + " to account " + toAccountId,
                List.of(
                        new LedgerLineRequest(fromLiabilityId, EntryType.DEBIT, amount),
                        new LedgerLineRequest(toLiabilityId, EntryType.CREDIT, amount))));

        Transaction transaction = transactionRepository.insert(TransactionType.TRANSFER, fromAccountId, toAccountId, amount, journalEntryId);
        publishCompleted(transaction);
        return transaction;
    }

    public List<Transaction> findAll() {
        return transactionRepository.findAll();
    }

    private static void validateAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
    }

    private void publishCompleted(Transaction transaction) {
        events.publish(new TransactionCompletedEvent(
                transaction.id(),
                transaction.type(),
                transaction.fromAccountId(),
                transaction.toAccountId(),
                transaction.amount(),
                transaction.createdAt()));
    }
}
