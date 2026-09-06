import { http } from './http'
import type { Transaction } from './types'

export function fetchTransactions() {
  return http.get<Transaction[]>('/transactions').then((r) => r.data)
}
