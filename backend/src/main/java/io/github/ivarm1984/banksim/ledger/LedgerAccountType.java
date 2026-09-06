package io.github.ivarm1984.banksim.ledger;

public enum LedgerAccountType {
    /** Singleton asset account: cash the bank holds. */
    BANK_CASH,
    /** Singleton expense account: interest paid out to customers. */
    INTEREST_EXPENSE,
    /** Singleton income account: fees charged to customers. */
    FEE_INCOME,
    /** One per customer Account: what the bank owes that customer. */
    CUSTOMER_LIABILITY,
    /** Singleton asset account: the bank's reserves held at the central bank. */
    CENTRAL_BANK_RESERVES,
    /** Singleton equity account: the bank's own paid-in capital. */
    BANK_CAPITAL,
    /** One per Loan: what the customer owes the bank. */
    LOAN_RECEIVABLE,
    /** Singleton income account: interest earned on loans. */
    INTEREST_INCOME
}
