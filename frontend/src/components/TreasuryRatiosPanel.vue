<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { fetchTreasuryRatios } from '../api/treasury'
import type { EventFeedMessage, TreasuryRatioSnapshot } from '../api/types'
import { subscribe } from '../ws/stompClient'
import { formatPercent } from '../utils/format'

const ratios = ref<TreasuryRatioSnapshot | null>(null)

async function reload() {
  ratios.value = await fetchTreasuryRatios()
}

let unsubscribe: (() => void) | undefined

onMounted(() => {
  reload()
  unsubscribe = subscribe<EventFeedMessage>('/topic/events', (message) => {
    if (message.type === 'TREASURY_RATIOS_UPDATED') reload()
  })
})

onUnmounted(() => unsubscribe?.())

const rows = [
  { label: 'Loan-to-deposit ratio', key: 'loanToDepositRatio' as const, minimum: null },
  { label: 'Liquidity coverage ratio', key: 'liquidityCoverageRatio' as const, minimum: '100%' },
  { label: 'Net stable funding ratio', key: 'netStableFundingRatio' as const, minimum: '100%' },
  { label: 'Reserve coverage ratio', key: 'reserveCoverageRatio' as const, minimum: '100%' },
  { label: 'Capital adequacy ratio', key: 'capitalAdequacyRatio' as const, minimum: '8%' },
]
</script>

<template>
  <section>
    <h2 class="mb-3 font-serif text-lg text-ink">Treasury ratios</h2>
    <p v-if="!ratios" class="text-sm text-ink-soft">No ratio data yet — step the clock forward a day.</p>
    <table v-else class="w-full border-collapse text-sm">
      <thead>
        <tr class="border-b border-rule text-left text-ink-soft">
          <th class="py-2 font-normal">Ratio</th>
          <th class="py-2 text-right font-normal">Value</th>
          <th class="py-2 text-right font-normal">EU minimum</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="row in rows" :key="row.key" class="border-b border-rule/60">
          <td class="py-2 text-ink">{{ row.label }}</td>
          <td class="py-2 text-right font-mono tabular-nums text-ink">{{ formatPercent(ratios[row.key]) }}</td>
          <td class="py-2 text-right font-mono tabular-nums text-ink-soft">{{ row.minimum ?? '—' }}</td>
        </tr>
      </tbody>
    </table>
    <p v-if="ratios" class="mt-2 text-xs text-ink-soft/80">As of {{ ratios.snapshotDate }}</p>
  </section>
</template>
