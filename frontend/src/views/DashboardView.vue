<script setup lang="ts">
import { onMounted, onUnmounted } from 'vue'
import SimulationControls from '../components/SimulationControls.vue'
import AccountsList from '../components/AccountsList.vue'
import EventFeed from '../components/EventFeed.vue'
import EventStats from '../components/EventStats.vue'
import LedgerInspector from '../components/LedgerInspector.vue'
import TransactionLog from '../components/TransactionLog.vue'
import StatementViewer from '../components/StatementViewer.vue'
import { useAccountsStore } from '../stores/accounts'
import { useCustomersStore } from '../stores/customers'
import { subscribe } from '../ws/stompClient'
import type { EventFeedMessage } from '../api/types'

const accounts = useAccountsStore()
const customers = useCustomersStore()

const DRIFT_RESYNC_MS = 10_000
let resyncHandle: ReturnType<typeof setInterval> | undefined
let unsubscribe: (() => void) | undefined

onMounted(() => {
  customers.load()
  accounts.load()

  unsubscribe = subscribe<EventFeedMessage>('/topic/events', (message) => {
    // Not on TRANSACTION_COMPLETED - at seed scale that fires far too often for a full refetch;
    // the periodic resync below keeps the current page fresh instead.
    if (message.type === 'INTEREST_ACCRUAL_BATCH_COMPLETED') {
      accounts.load()
    }
  })

  resyncHandle = setInterval(() => accounts.load(), DRIFT_RESYNC_MS)
})

onUnmounted(() => {
  clearInterval(resyncHandle)
  unsubscribe?.()
})
</script>

<template>
  <div class="min-h-screen bg-paper text-ink">
    <SimulationControls />

    <main class="mx-auto grid max-w-6xl gap-x-10 gap-y-10 px-6 py-8 md:grid-cols-2">
      <AccountsList />
      <EventFeed />

      <div class="md:col-span-2">
        <EventStats />
      </div>

      <div class="md:col-span-2">
        <LedgerInspector />
      </div>

      <TransactionLog />
      <StatementViewer />
    </main>
  </div>
</template>
