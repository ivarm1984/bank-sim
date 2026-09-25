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

export interface PolicyLeversSnapshot {
  savingsRateSpread: number
  mortgageSpreadAdjustment: number
  consumerSpreadAdjustment: number
  businessSpreadAdjustment: number
  targetCapitalBuffer: number
  underwritingLooseness: number
  autoTapBorrowingFacility: boolean
}

export interface TreasuryRatioSnapshot {
  id: number
  snapshotDate: string
  bankCash: number
  centralBankReserves: number
  loansReceivable: number
  loanLossProvision: number
  customerDeposits: number
  capitalBase: number
  loanToDepositRatio: number | null
  liquidityCoverageRatio: number | null
  netStableFundingRatio: number | null
  requiredReserves: number | null
  reserveCoverageRatio: number | null
  capitalAdequacyRatio: number | null
  riskWeightedAssets: number
  /** Average annual return on equity since the epoch; null on day one. */
  returnOnEquity: number | null
  /** Gross Stage 3 (defaulted) loans / gross loans; null while there are no loans. */
  nonPerformingLoanRatio: number | null
  createdAt: string
}

export type BankHealthStatus = 'PLAYING' | 'WARNING' | 'GAME_OVER' | 'BANK_RUN' | 'WON'

export interface BankHealthSnapshot {
  id: number
  snapshotDate: string
  status: BankHealthStatus
  /** Consecutive days below OCR (into the combined buffer). */
  capitalBreachStreak: number
  /** Consecutive days below TSCR (failing or likely to fail). */
  capitalShortfallStreak: number
  /** Consecutive days with NSFR below 100%. */
  fundingBreachStreak: number
  liquidityBreachStreak: number
  createdAt: string
}

export interface EventInjectorStatus {
  cumulativeRateOffset: number
  recessionActive: boolean
}
