import { http } from './http'
import type { TreasuryRatioSnapshot } from './types'

/** 404 until at least one simulated day has passed - callers treat that as "no snapshot yet". */
export function fetchTreasuryRatios() {
  return http
    .get<TreasuryRatioSnapshot>('/treasury/ratios')
    .then((r) => r.data)
    .catch((err) => {
      if (err.response?.status === 404) return null
      throw err
    })
}
