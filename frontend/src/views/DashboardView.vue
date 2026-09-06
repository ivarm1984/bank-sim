<script setup lang="ts">
import { onMounted, onUnmounted } from 'vue'
import SimulationControls from '../components/SimulationControls.vue'
import AccountsList from '../components/AccountsList.vue'
import EventFeed from '../components/EventFeed.vue'
import LedgerInspector from '../components/LedgerInspector.vue'
import TransactionLog from '../components/TransactionLog.vue'
import StatementViewer from '../components/StatementViewer.vue'
import { useAccountsStore } from '../stores/accounts'
import { useCustomersStore } from '../stores/customers'
import { useClockStore } from '../stores/clock'
import { useEventFeedStore } from '../stores/eventFeed'
import { subscribe } from '../ws/stompClient'
import type { ClockSnapshot, EventFeedMessage } from '../api/types'

const accounts = useAccountsStore()
const customers = useCustomersStore()
const clock = useClockStore()
const eventFeed = useEventFeedStore()

const DRIFT_RESYNC_MS = 10_000
let resyncHandle: ReturnType<typeof setInterval> | undefined

onMounted(() => {
  customers.load()
  accounts.load()

  subscribe<ClockSnapshot>('/topic/clock', clock.patch)
  subscribe<EventFeedMessage>('/topic/events', (message) => {
    eventFeed.push(message)
    if (message.type === 'TRANSACTION_COMPLETED' || message.type === 'INTEREST_ACCRUAL_BATCH_COMPLETED') {
      accounts.load()
    }
  })

  resyncHandle = setInterval(() => accounts.load(), DRIFT_RESYNC_MS)
})

onUnmounted(() => clearInterval(resyncHandle))
</script>

<template>
  <div class="min-h-screen bg-paper text-ink">
    <SimulationControls />

    <main class="mx-auto grid max-w-6xl gap-x-10 gap-y-10 px-6 py-8 md:grid-cols-2">
      <AccountsList />
      <EventFeed />

      <div class="md:col-span-2">
        <LedgerInspector />
      </div>

      <TransactionLog />
      <StatementViewer />
    </main>
  </div>
</template>
