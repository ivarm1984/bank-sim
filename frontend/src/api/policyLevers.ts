import { http } from './http'
import type { PolicyLeversSnapshot } from './types'

export function fetchPolicyLevers() {
  return http.get<PolicyLeversSnapshot>('/policy-levers').then((r) => r.data)
}

export function updatePolicyLevers(snapshot: PolicyLeversSnapshot) {
  return http.post<PolicyLeversSnapshot>('/policy-levers', snapshot).then((r) => r.data)
}
