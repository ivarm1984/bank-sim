import { http } from './http'
import type { ClockSnapshot } from './types'

export function fetchClockState() {
  return http.get<ClockSnapshot>('/clock/state').then((r) => r.data)
}

export function play() {
  return http.post<ClockSnapshot>('/clock/play').then((r) => r.data)
}

export function pause() {
  return http.post<ClockSnapshot>('/clock/pause').then((r) => r.data)
}

export function reset() {
  return http.post<ClockSnapshot>('/clock/reset').then((r) => r.data)
}

export function stepDay() {
  return http.post<ClockSnapshot>('/clock/step-day').then((r) => r.data)
}

export function setSpeed(minutesPerTick: number) {
  return http.post<ClockSnapshot>('/clock/speed', { minutesPerTick }).then((r) => r.data)
}
