package io.github.ivarm1984.banksim.account;

/** One account to open, used by {@link AccountService#openBatch(java.util.List)} bulk seeding. */
public record AccountOpenRequest(long customerId, AccountType accountType) {
}
