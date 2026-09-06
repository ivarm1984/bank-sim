<script setup lang="ts">
import { useEventFeedStore } from '../stores/eventFeed'
import { formatCurrency } from '../utils/format'

const feed = useEventFeedStore()
</script>

<template>
  <section>
    <h2 class="mb-3 font-serif text-lg text-ink">Activity since page load</h2>
    <dl class="grid grid-cols-2 gap-3 text-sm">
      <div class="border border-rule/60 p-2">
        <dt class="text-ink-soft">Transactions</dt>
        <dd class="font-mono tabular-nums text-ink">{{ feed.totalTransactions }}</dd>
      </div>
      <div class="border border-rule/60 p-2">
        <dt class="text-ink-soft">Transaction volume</dt>
        <dd class="font-mono tabular-nums text-ink">{{ formatCurrency(feed.totalTransactionVolume) }}</dd>
      </div>
      <div class="border border-rule/60 p-2">
        <dt class="text-ink-soft">Loans originated</dt>
        <dd class="font-mono tabular-nums text-ink">{{ feed.totalLoansOriginated }}</dd>
      </div>
      <div class="border border-rule/60 p-2">
        <dt class="text-ink-soft">Loan payments made</dt>
        <dd class="font-mono tabular-nums text-ink">{{ feed.totalLoanPayments }}</dd>
      </div>
      <div class="col-span-2 border border-rule/60 p-2">
        <dt class="text-ink-soft">Loan payments in the last minute</dt>
        <dd class="font-mono tabular-nums text-ink">
          {{ feed.paymentsInLastMinuteCount }} ({{ formatCurrency(feed.paymentsInLastMinuteVolume) }})
        </dd>
      </div>
    </dl>
  </section>
</template>
