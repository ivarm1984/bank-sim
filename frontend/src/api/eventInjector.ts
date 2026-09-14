import { http } from './http'
import type { EventInjectorStatus } from './types'

export function fetchEventInjectorStatus() {
  return http.get<EventInjectorStatus>('/event-injector/status').then((r) => r.data)
}
