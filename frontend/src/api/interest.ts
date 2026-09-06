import { http } from './http'
import type { InterestAccrual } from './types'

export function fetchInterestAccruals(accountId: number) {
  return http.get<InterestAccrual[]>(`/accounts/${accountId}/interest-accruals`).then((r) => r.data)
}
