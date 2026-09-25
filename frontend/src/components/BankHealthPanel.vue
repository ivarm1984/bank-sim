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
      <p class="font-mono text-sm tabular-nums text-ink-soft">
        Capital breach streak {{ health.capitalBreachStreak }} · Liquidity breach streak {{ health.liquidityBreachStreak }}
      </p>
      <p class="text-xs text-ink-soft/80">As of {{ health.snapshotDate }}</p>
    </div>
  </section>
</template>
