import { http } from './http'
import type { LedgerAccountBalance, TrialBalance } from './types'

export function fetchLedgerAccounts() {
  return http.get<LedgerAccountBalance[]>('/ledger/accounts').then((r) => r.data)
}

export function fetchTrialBalance() {
  return http.get<TrialBalance>('/ledger/trial-balance').then((r) => r.data)
}
