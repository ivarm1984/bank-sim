import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import type { EventFeedMessage } from '../api/types'

const MAX_ENTRIES = 200
// Above this, a TRANSACTION_COMPLETED is "big enough" to show in the feed - everything else still
// counts toward the rolling stats below, it just doesn't clutter the visible list.
const SIGNIFICANT_TRANSACTION_AMOUNT = 1000
// occurredAt on the wire is wall-clock (when the backend published it), not simulated time, so this
// window is real seconds - at a fast simulated speed that still represents a lot of activity.
const RECENT_PAYMENTS_WINDOW_MS = 60_000

interface AmountPayload {
  amount?: number
  principal?: number
  paidOff?: boolean
}

function amountOf(message: EventFeedMessage): number {
  const payload = message.payload as AmountPayload
  return payload.amount ?? payload.principal ?? 0
}

function isSignificant(message: EventFeedMessage): boolean {
  switch (message.type) {
    case 'DAY_ROLLED_OVER':
    case 'INTEREST_ACCRUAL_BATCH_COMPLETED':
    case 'BILL_PAYMENT_FAILED':
    case 'LOAN_ORIGINATED':
      return true
    case 'LOAN_REPAID':
      return Boolean((message.payload as AmountPayload).paidOff)
    case 'TRANSACTION_COMPLETED':
      return amountOf(message) >= SIGNIFICANT_TRANSACTION_AMOUNT
    default:
      return false
  }
}

export const useEventFeedStore = defineStore('eventFeed', () => {
  const entries = ref<EventFeedMessage[]>([])

  const totalTransactions = ref(0)
  const totalTransactionVolume = ref(0)
  const totalLoansOriginated = ref(0)
  const totalLoanPayments = ref(0)
  const recentPayments = ref<{ at: number; amount: number }[]>([])

  const paymentsInLastMinute = computed(() => {
    const cutoff = Date.now() - RECENT_PAYMENTS_WINDOW_MS
    return recentPayments.value.filter((p) => p.at >= cutoff)
  })
  const paymentsInLastMinuteCount = computed(() => paymentsInLastMinute.value.length)
  const paymentsInLastMinuteVolume = computed(() => paymentsInLastMinute.value.reduce((sum, p) => sum + p.amount, 0))

  function push(message: EventFeedMessage) {
    tally(message)
    if (isSignificant(message)) {
      entries.value.unshift(message)
      if (entries.value.length > MAX_ENTRIES) {
        entries.value.length = MAX_ENTRIES
      }
    }
  }

  function tally(message: EventFeedMessage) {
    if (message.type === 'TRANSACTION_COMPLETED') {
      totalTransactions.value += 1
      totalTransactionVolume.value += amountOf(message)
    } else if (message.type === 'LOAN_ORIGINATED') {
      totalLoansOriginated.value += 1
    } else if (message.type === 'LOAN_REPAID') {
      totalLoanPayments.value += 1
      const cutoff = Date.now() - RECENT_PAYMENTS_WINDOW_MS
      recentPayments.value.push({ at: Date.now(), amount: amountOf(message) })
      while (recentPayments.value.length > 0 && recentPayments.value[0].at < cutoff) {
        recentPayments.value.shift()
      }
    }
  }

  return {
    entries,
    push,
    totalTransactions,
    totalTransactionVolume,
    totalLoansOriginated,
    totalLoanPayments,
    paymentsInLastMinuteCount,
    paymentsInLastMinuteVolume,
  }
})
