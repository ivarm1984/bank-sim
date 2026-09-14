import { http } from './http'
import type { BankHealthSnapshot } from './types'

/** 404 until at least one simulated day has passed - callers treat that as "no snapshot yet". */
export function fetchBankHealth() {
  return http
    .get<BankHealthSnapshot>('/bank-health/status')
    .then((r) => r.data)
    .catch((err) => {
      if (err.response?.status === 404) return null
      throw err
    })
}
