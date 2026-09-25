<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { fetchEventInjectorStatus } from '../api/eventInjector'
import type { EventFeedMessage, EventInjectorStatus } from '../api/types'
import { subscribe } from '../ws/stompClient'
import { formatPercent } from '../utils/format'

const status = ref<EventInjectorStatus | null>(null)

async function reload() {
  status.value = await fetchEventInjectorStatus()
}

let unsubscribe: (() => void) | undefined

onMounted(() => {
  reload()
  unsubscribe = subscribe<EventFeedMessage>('/topic/events', (message) => {
    if (['RATE_SHOCK_TRIGGERED', 'RECESSION_STARTED', 'RECESSION_ENDED'].includes(message.type)) reload()
  })
})

onUnmounted(() => unsubscribe?.())
</script>

<template>
  <section>
    <h2 class="mb-3 font-serif text-lg text-ink">Event injector</h2>
    <div v-if="status" class="flex flex-col gap-2 text-sm">
      <p class="text-ink">
        Cumulative rate shock offset:
        <span class="font-mono tabular-nums" :class="status.cumulativeRateOffset >= 0 ? 'text-debit' : 'text-credit'">
          {{ status.cumulativeRateOffset >= 0 ? '+' : '' }}{{ formatPercent(status.cumulativeRateOffset) }}
        </span>
      </p>
      <p class="text-ink">
        Recession:
        <span class="font-medium" :class="status.recessionActive ? 'text-debit' : 'text-credit'">
          {{ status.recessionActive ? 'active' : 'inactive' }}
        </span>
      </p>
    </div>
  </section>
</template>
