<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { fetchBankHealth } from '../api/bankHealth'
import type { BankHealthSnapshot, EventFeedMessage } from '../api/types'
import { subscribe } from '../ws/stompClient'

const health = ref<BankHealthSnapshot | null>(null)

async function reload() {
  health.value = await fetchBankHealth()
}

let unsubscribe: (() => void) | undefined

onMounted(() => {
  reload()
  unsubscribe = subscribe<EventFeedMessage>('/topic/events', (message) => {
    if (message.type === 'BANK_HEALTH_UPDATED') reload()
  })
})

onUnmounted(() => unsubscribe?.())

// Days-in-a-row counters: 5 of any -> regulator warning; 10 below TSCR -> game over; 10 of
// uncovered liquidity -> bank run. NSFR only ever warns.
const streaks = [
  { key: 'capitalBreachStreak' as const, label: 'Below OCR (12.5%)' },
  { key: 'capitalShortfallStreak' as const, label: 'Below TSCR (10%)' },
  { key: 'fundingBreachStreak' as const, label: 'NSFR below 100%' },
  { key: 'liquidityBreachStreak' as const, label: 'Uncovered liquidity' },
]

const statusColor: Record<string, string> = {
  PLAYING: 'text-credit',
  WARNING: 'text-accent',
  GAME_OVER: 'text-debit',
  BANK_RUN: 'text-debit',
  WON: 'text-credit',
}

const statusLabel: Record<string, string> = {
  PLAYING: 'Playing',
  WARNING: 'Regulator warning',
  GAME_OVER: 'Game over — forced resolution',
  BANK_RUN: 'Game over — bank run',
  WON: 'You survived',
}
</script>

<template>
  <section>
    <h2 class="mb-3 font-serif text-lg text-ink">Bank health</h2>
    <p v-if="!health" class="text-sm text-ink-soft">No health data yet — step the clock forward a day.</p>
    <div v-else class="flex flex-col gap-2">
      <p class="text-lg font-medium" :class="statusColor[health.status]">{{ statusLabel[health.status] }}</p>
      <dl class="grid grid-cols-[1fr_auto] gap-x-4 gap-y-1 text-sm">
        <template v-for="streak in streaks" :key="streak.key">
          <dt class="text-ink-soft">{{ streak.label }}</dt>
          <dd class="text-right font-mono tabular-nums text-ink">{{ health[streak.key] }} d</dd>
        </template>
      </dl>
      <p class="text-xs text-ink-soft/80">As of {{ health.snapshotDate }}</p>
    </div>
  </section>
</template>
