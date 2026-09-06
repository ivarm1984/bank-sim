import { http } from './http'
import type { Account } from './types'

export function fetchAccounts() {
  return http.get<Account[]>('/accounts').then((r) => r.data)
}

export function fetchAccountsPage(limit: number, offset: number) {
  return http.get<Account[]>('/accounts', { params: { limit, offset } }).then((r) => r.data)
}
