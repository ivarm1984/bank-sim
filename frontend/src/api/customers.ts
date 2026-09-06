import { http } from './http'
import type { Customer } from './types'

export function fetchCustomers() {
  return http.get<Customer[]>('/customers').then((r) => r.data)
}
