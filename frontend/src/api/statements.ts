import { http } from './http'
import type { Statement } from './types'

export function fetchStatements(accountId: number) {
  return http.get<Statement[]>(`/accounts/${accountId}/statements`).then((r) => r.data)
}
