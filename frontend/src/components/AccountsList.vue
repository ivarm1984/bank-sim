<script setup lang="ts">
import { computed } from 'vue'
import { useAccountsStore } from '../stores/accounts'
import { useCustomersStore } from '../stores/customers'
import { formatCurrency } from '../utils/format'

const accounts = useAccountsStore()
const customers = useCustomersStore()

const rows = computed(() =>
  accounts.list
    .map((account) => ({
      account,
      customerName: customers.list.find((c) => c.id === account.customerId)?.fullName ?? `Customer ${account.customerId}`,
    }))
    .sort((a, b) => a.account.id - b.account.id),
)
</script>

<template>
  <section>
    <h2 class="mb-3 font-serif text-lg text-ink">Accounts</h2>
    <table class="w-full border-collapse text-sm">
      <thead>
        <tr class="border-b border-rule text-left text-ink-soft">
          <th class="py-2 font-normal">Customer</th>
          <th class="py-2 font-normal">Type</th>
          <th class="py-2 text-right font-normal">Balance</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="row in rows" :key="row.account.id" class="border-b border-rule/60">
          <td class="py-2 text-ink">{{ row.customerName }}</td>
          <td class="py-2 text-ink-soft">{{ row.account.accountType }}</td>
          <td class="py-2 text-right font-mono tabular-nums text-ink">{{ formatCurrency(row.account.currentBalance) }}</td>
        </tr>
        <tr v-if="rows.length === 0">
          <td colspan="3" class="py-4 text-center text-ink-soft">No accounts yet.</td>
        </tr>
      </tbody>
    </table>
  </section>
</template>
