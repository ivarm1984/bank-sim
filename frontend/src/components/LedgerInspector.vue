<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { fetchLedgerAccounts, fetchTrialBalance } from '../api/ledger'
import type { EventFeedMessage, LedgerAccountBalance, TrialBalance } from '../api/types'
import { subscribe } from '../ws/stompClient'
import { formatCurrency } from '../utils/format'

const ledgerAccounts = ref<LedgerAccountBalance[]>([])
const trialBalance = ref<TrialBalance | null>(null)

async function reload() {
  ;[ledgerAccounts.value, trialBalance.value] = await Promise.all([fetchLedgerAccounts(), fetchTrialBalance()])
}

let unsubscribe: (() => void) | undefined

onMounted(() => {
  reload()
  unsubscribe = subscribe<EventFeedMessage>('/topic/events', (message) => {
    if (message.type === 'TRANSACTION_COMPLETED' || message.type === 'INTEREST_ACCRUAL_BATCH_COMPLETED') reload()
  })
})

onUnmounted(() => unsubscribe?.())
</script>

<template>
  <section>
    <div class="mb-3 flex items-center justify-between">
      <h2 class="font-serif text-lg text-ink">Ledger</h2>
      <p v-if="trialBalance" class="font-mono text-sm tabular-nums" :class="trialBalance.balanced ? 'text-credit' : 'text-debit'">
        Debits {{ formatCurrency(trialBalance.totalDebits) }} · Credits {{ formatCurrency(trialBalance.totalCredits) }}
        — {{ trialBalance.balanced ? 'balanced' : 'out of balance' }}
      </p>
    </div>
    <table class="w-full border-collapse text-sm">
      <thead>
        <tr class="border-b border-rule text-left text-ink-soft">
          <th class="py-2 font-normal">Ledger account</th>
          <th class="py-2 font-normal">Type</th>
          <th class="py-2 text-right font-normal">Total debits</th>
          <th class="py-2 text-right font-normal">Total credits</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="a in ledgerAccounts" :key="a.id" class="border-b border-rule/60">
          <td class="py-2 text-ink">{{ a.name }}</td>
          <td class="py-2 text-ink-soft">{{ a.type }}</td>
          <td class="py-2 text-right font-mono tabular-nums text-debit">{{ formatCurrency(a.totalDebits) }}</td>
          <td class="py-2 text-right font-mono tabular-nums text-credit">{{ formatCurrency(a.totalCredits) }}</td>
        </tr>
      </tbody>
    </table>
  </section>
</template>
