import { http } from './http'
import type { Account } from './types'

export function fetchAccounts() {
  return http.get<Account[]>('/accounts').then((r) => r.data)
}
