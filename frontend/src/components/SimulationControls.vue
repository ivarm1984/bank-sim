<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useClockStore } from '../stores/clock'
import { subscribe } from '../ws/stompClient'
import type { ClockSnapshot } from '../api/types'
import { formatDateTime } from '../utils/format'

const clock = useClockStore()
const speedInput = ref(clock.state?.speed ?? 60)

// Lives in the shared header (mounted on every view), so the clock keeps ticking live on /ceo too.
const unsubscribe = subscribe<ClockSnapshot>('/topic/clock', clock.patch)
onUnmounted(unsubscribe)

onMounted(async () => {
  await clock.load()
  speedInput.value = clock.state?.speed ?? 60
})

watch(
  () => clock.state?.speed,
  (speed) => {
    if (speed !== undefined) speedInput.value = speed
  },
)

const simulatedTime = computed(() => (clock.state ? formatDateTime(clock.state.simulatedTime) : '—'))

function togglePlay() {
  if (clock.state?.running) clock.pause()
  else clock.play()
}

function applySpeed() {
  if (speedInput.value > 0) clock.setSpeed(speedInput.value)
}
</script>

<template>
  <header class="flex flex-wrap items-center justify-between gap-4 border-b border-rule px-6 py-4">
    <div class="flex items-center gap-3">
      <span
        class="h-2.5 w-2.5 rounded-full"
        :class="clock.state?.running ? 'bg-accent animate-pulse' : 'bg-rule'"
        aria-hidden="true"
      ></span>
      <h1 class="font-serif text-2xl tracking-tight text-ink">Bank-Sim</h1>
      <span class="font-mono text-lg tabular-nums text-ink-soft">{{ simulatedTime }}</span>
      <nav class="flex items-center gap-3 text-sm">
        <RouterLink to="/" class="text-ink-soft hover:text-ink" active-class="font-medium text-ink underline">Dashboard</RouterLink>
        <RouterLink to="/ceo" class="text-ink-soft hover:text-ink" active-class="font-medium text-ink underline">CEO mode</RouterLink>
      </nav>
    </div>

    <div class="flex flex-wrap items-center gap-2">
      <button
        type="button"
        class="rounded border border-rule px-3 py-1.5 text-sm font-medium text-ink hover:border-ink"
        @click="togglePlay"
      >
        {{ clock.state?.running ? 'Pause' : 'Play' }}
      </button>
      <button
        type="button"
        class="rounded border border-rule px-3 py-1.5 text-sm font-medium text-ink hover:border-ink"
        @click="clock.stepDay()"
      >
        Step day
      </button>
      <button
        type="button"
        class="rounded border border-rule px-3 py-1.5 text-sm font-medium text-ink hover:border-ink"
        @click="clock.reset()"
      >
        Reset
      </button>
      <label class="flex items-center gap-2 text-sm text-ink-soft">
        Speed
        <input
          v-model.number="speedInput"
          type="number"
          min="1"
          class="w-20 rounded border border-rule bg-paper px-2 py-1 font-mono text-sm tabular-nums text-ink"
          @change="applySpeed"
        />
        min/tick
      </label>
    </div>
  </header>
</template>
