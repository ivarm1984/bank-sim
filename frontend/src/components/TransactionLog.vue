<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { fetchTransactions } from '../api/transactions'
import type { EventFeedMessage, Transaction } from '../api/types'
import { subscribe } from '../ws/stompClient'
import { formatCurrency, formatDateTime } from '../utils/format'

const transactions = ref<Transaction[]>([])

async function reload() {
  transactions.value = (await fetchTransactions()).sort((a, b) => b.id - a.id)
}

let unsubscribe: (() => void) | undefined

onMounted(() => {
  reload()
  unsubscribe = subscribe<EventFeedMessage>('/topic/events', (message) => {
    if (message.type === 'TRANSACTION_COMPLETED') reload()
  })
})

onUnmounted(() => unsubscribe?.())

const toneClass: Record<Transaction['type'], string> = {
  DEPOSIT: 'text-credit',
  WITHDRAWAL: 'text-debit',
  TRANSFER: 'text-ink',
}
</script>

<template>
  <section>
    <h2 class="mb-3 font-serif text-lg text-ink">Transactions</h2>
    <table class="w-full border-collapse text-sm">
      <thead>
        <tr class="border-b border-rule text-left text-ink-soft">
          <th class="py-2 font-normal">Time</th>
          <th class="py-2 font-normal">Type</th>
          <th class="py-2 font-normal">From</th>
          <th class="py-2 font-normal">To</th>
          <th class="py-2 text-right font-normal">Amount</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="t in transactions.slice(0, 25)" :key="t.id" class="border-b border-rule/60">
          <td class="py-2 font-mono text-xs tabular-nums text-ink-soft">{{ formatDateTime(t.createdAt) }}</td>
          <td class="py-2" :class="toneClass[t.type]">{{ t.type }}</td>
          <td class="py-2 text-ink-soft">{{ t.fromAccountId ?? '—' }}</td>
          <td class="py-2 text-ink-soft">{{ t.toAccountId ?? '—' }}</td>
          <td class="py-2 text-right font-mono tabular-nums text-ink">{{ formatCurrency(t.amount) }}</td>
        </tr>
        <tr v-if="transactions.length === 0">
          <td colspan="5" class="py-4 text-center text-ink-soft">No transactions yet.</td>
        </tr>
      </tbody>
    </table>
  </section>
</template>
