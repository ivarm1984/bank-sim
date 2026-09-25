<script setup lang="ts">
import { computed } from 'vue'
import { useEventFeedStore } from '../stores/eventFeed'
import { formatCurrency, formatDateTime } from '../utils/format'

const feed = useEventFeedStore()

interface TransactionPayload {
  transactionId: number
  type: 'DEPOSIT' | 'WITHDRAWAL' | 'TRANSFER'
  fromAccountId: number | null
  toAccountId: number | null
  amount: number
  createdAt: string
}
interface BillPaymentFailedPayload {
  accountId: number
  amount: number
  simulatedNow: string
}
interface InterestBatchPayload {
  date: string
}
interface DayRolledOverPayload {
  newDate: string
}
interface LoanOriginatedPayload {
  loanId: number
  loanType: 'MORTGAGE' | 'CONSUMER'
  principal: number
}
interface LoanWrittenOffPayload {
  loanId: number
  loanType: 'MORTGAGE' | 'CONSUMER' | 'BUSINESS'
  outstandingPrincipal: number
  recoveryAmount: number
}
interface LoanRepaidPayload {
  loanId: number
  amount: number
  paidOff: boolean
}

function describe(type: string, payload: unknown): { text: string; tone: 'credit' | 'debit' | 'neutral' } {
  switch (type) {
    case 'TRANSACTION_COMPLETED': {
      const p = payload as TransactionPayload
      if (p.type === 'DEPOSIT') return { text: `Deposit of ${formatCurrency(p.amount)} to account ${p.toAccountId}`, tone: 'credit' }
      if (p.type === 'WITHDRAWAL') return { text: `Withdrawal of ${formatCurrency(p.amount)} from account ${p.fromAccountId}`, tone: 'debit' }
      return { text: `Transfer of ${formatCurrency(p.amount)}: account ${p.fromAccountId} → ${p.toAccountId}`, tone: 'neutral' }
    }
    case 'BILL_PAYMENT_FAILED': {
      const p = payload as BillPaymentFailedPayload
      return { text: `Bill payment of ${formatCurrency(p.amount)} failed for account ${p.accountId} - insufficient funds`, tone: 'debit' }
    }
    case 'INTEREST_ACCRUAL_BATCH_COMPLETED': {
      const p = payload as InterestBatchPayload
      return { text: `Interest accrual batch completed for ${p.date}`, tone: 'neutral' }
    }
    case 'DAY_ROLLED_OVER': {
      const p = payload as DayRolledOverPayload
      return { text: `Simulated day rolled over to ${p.newDate}`, tone: 'neutral' }
    }
    case 'LOAN_ORIGINATED': {
      const p = payload as LoanOriginatedPayload
      const label = p.loanType === 'MORTGAGE' ? 'Mortgage' : 'Consumer loan'
      return { text: `${label} of ${formatCurrency(p.principal)} originated (loan ${p.loanId})`, tone: 'debit' }
    }
    case 'LOAN_REPAID': {
      const p = payload as LoanRepaidPayload
      return { text: `Loan ${p.loanId} paid off with a final payment of ${formatCurrency(p.amount)}`, tone: 'credit' }
    }
    case 'LOAN_WRITTEN_OFF': {
      const p = payload as LoanWrittenOffPayload
      return {
        text: `Loan ${p.loanId} written off: ${formatCurrency(p.outstandingPrincipal)} outstanding, ${formatCurrency(p.recoveryAmount)} recovered`,
        tone: 'debit',
      }
    }
    default:
      return { text: type, tone: 'neutral' }
  }
}

const rows = computed(() =>
  feed.entries.map((entry) => ({ ...entry, ...describe(entry.type, entry.payload) })),
)

const toneClass = { credit: 'text-credit', debit: 'text-debit', neutral: 'text-ink' } as const
</script>

<template>
  <section>
    <h2 class="mb-3 font-serif text-lg text-ink">Event feed</h2>
    <ol class="max-h-96 space-y-1 overflow-y-auto text-sm">
      <li v-for="(row, i) in rows" :key="i" class="flex gap-3 border-b border-rule/60 py-1.5">
        <span class="shrink-0 font-mono tabular-nums text-ink-soft">{{ formatDateTime(row.occurredAt).slice(11) }}</span>
        <span :class="toneClass[row.tone]">{{ row.text }}</span>
      </li>
      <li v-if="rows.length === 0" class="py-4 text-center text-ink-soft">Waiting for activity…</li>
    </ol>
  </section>
</template>
