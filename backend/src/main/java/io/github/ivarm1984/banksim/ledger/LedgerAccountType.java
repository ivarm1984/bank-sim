package io.github.ivarm1984.banksim.ledger;

public enum LedgerAccountType {
    /** Singleton asset account: cash the bank holds. */
    BANK_CASH,
    /** Singleton expense account: interest paid out to customers. */
    INTEREST_EXPENSE,
    /** Singleton income account: fees charged to customers. */
    FEE_INCOME,
    /** One per customer Account: what the bank owes that customer. */
    CUSTOMER_LIABILITY
}
