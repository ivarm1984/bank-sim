export type AccountType = 'CHECKING' | 'SAVINGS' | 'TERM_DEPOSIT'
export type TransactionType = 'DEPOSIT' | 'WITHDRAWAL' | 'TRANSFER'
export type LedgerAccountType = 'BANK_CASH' | 'INTEREST_EXPENSE' | 'FEE_INCOME' | 'CUSTOMER_LIABILITY'

export interface Customer {
  id: number
  fullName: string
  createdAt: string
}

export interface Account {
  id: number
  customerId: number
  accountType: AccountType
  currentBalance: number
  createdAt: string
}

export interface Transaction {
  id: number
  type: TransactionType
  fromAccountId: number | null
  toAccountId: number | null
  amount: number
  journalEntryId: number
  createdAt: string
}

export interface ClockSnapshot {
  simulatedTime: string
  running: boolean
  speed: number
}

export interface InterestAccrual {
  id: number
  accountId: number
  accrualDate: string
  principalBalance: number
  annualRate: number
  amount: number
  journalEntryId: number
  createdAt: string
}

export interface Statement {
  id: number
  accountId: number
  statementDate: string
  openingBalance: number
  closingBalance: number
  createdAt: string
}

export interface LedgerAccountBalance {
  id: number
  type: LedgerAccountType
  accountId: number | null
  name: string
  totalDebits: number
  totalCredits: number
}

export interface TrialBalance {
  totalDebits: number
  totalCredits: number
  balanced: boolean
}

export interface EventFeedMessage {
  type: string
  payload: unknown
  occurredAt: string
}
