<script setup lang="ts">
import { ref, watch } from 'vue'
import { useAccountsStore } from '../stores/accounts'
import { fetchStatements } from '../api/statements'
import type { Statement } from '../api/types'
import { formatCurrency } from '../utils/format'

const accounts = useAccountsStore()
const selectedAccountId = ref<number | null>(null)
const statements = ref<Statement[]>([])

watch(
  () => accounts.list,
  (list) => {
    if (selectedAccountId.value === null && list.length > 0) selectedAccountId.value = list[0].id
  },
  { immediate: true },
)

watch(
  selectedAccountId,
  async (accountId) => {
    statements.value = accountId ? (await fetchStatements(accountId)).sort((a, b) => b.id - a.id) : []
  },
  { immediate: true },
)
</script>

<template>
  <section>
    <div class="mb-3 flex items-center justify-between">
      <h2 class="font-serif text-lg text-ink">Statements</h2>
      <select
        v-model.number="selectedAccountId"
        class="rounded border border-rule bg-paper px-2 py-1 text-sm text-ink"
      >
        <option v-for="account in accounts.list" :key="account.id" :value="account.id">Account {{ account.id }}</option>
      </select>
    </div>
    <table class="w-full border-collapse text-sm">
      <thead>
        <tr class="border-b border-rule text-left text-ink-soft">
          <th class="py-2 font-normal">Date</th>
          <th class="py-2 text-right font-normal">Opening</th>
          <th class="py-2 text-right font-normal">Closing</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="s in statements" :key="s.id" class="border-b border-rule/60">
          <td class="py-2 text-ink-soft">{{ s.statementDate }}</td>
          <td class="py-2 text-right font-mono tabular-nums text-ink">{{ formatCurrency(s.openingBalance) }}</td>
          <td class="py-2 text-right font-mono tabular-nums text-ink">{{ formatCurrency(s.closingBalance) }}</td>
        </tr>
        <tr v-if="statements.length === 0">
          <td colspan="3" class="py-4 text-center text-ink-soft">No statements yet.</td>
        </tr>
      </tbody>
    </table>
  </section>
</template>
