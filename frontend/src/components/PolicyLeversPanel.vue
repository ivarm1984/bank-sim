<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { usePolicyLeversStore } from '../stores/policyLevers'

const policyLevers = usePolicyLeversStore()

// Drafts are held in percent (not the raw 0-1 decimal fraction the API uses) since that's
// what a CEO actually thinks in - converted back to decimals only on Apply.
const savingsRateSpreadPct = ref(0)
const mortgageSpreadAdjustmentPct = ref(0)
const consumerSpreadAdjustmentPct = ref(0)
const businessSpreadAdjustmentPct = ref(0)
const targetCapitalBufferPct = ref(0)
const underwritingLoosenessPct = ref(0)
const autoTapBorrowingFacility = ref(true)
const saving = ref(false)

function syncDraftFromState() {
  const s = policyLevers.state
  if (!s) return
  savingsRateSpreadPct.value = s.savingsRateSpread * 100
  mortgageSpreadAdjustmentPct.value = s.mortgageSpreadAdjustment * 100
  consumerSpreadAdjustmentPct.value = s.consumerSpreadAdjustment * 100
  businessSpreadAdjustmentPct.value = s.businessSpreadAdjustment * 100
  targetCapitalBufferPct.value = s.targetCapitalBuffer * 100
  underwritingLoosenessPct.value = s.underwritingLooseness * 100
  autoTapBorrowingFacility.value = s.autoTapBorrowingFacility
}

onMounted(async () => {
  await policyLevers.load()
  syncDraftFromState()
})

watch(() => policyLevers.state, syncDraftFromState)

async function apply() {
  saving.value = true
  try {
    await policyLevers.update({
      savingsRateSpread: savingsRateSpreadPct.value / 100,
      mortgageSpreadAdjustment: mortgageSpreadAdjustmentPct.value / 100,
      consumerSpreadAdjustment: consumerSpreadAdjustmentPct.value / 100,
      businessSpreadAdjustment: businessSpreadAdjustmentPct.value / 100,
      targetCapitalBuffer: targetCapitalBufferPct.value / 100,
      underwritingLooseness: underwritingLoosenessPct.value / 100,
      autoTapBorrowingFacility: autoTapBorrowingFacility.value,
    })
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <section>
    <h2 class="mb-3 font-serif text-lg text-ink">Policy levers</h2>
    <div class="grid gap-4 sm:grid-cols-2">
      <label class="flex flex-col gap-1 text-sm text-ink-soft">
        Savings rate spread (%)
        <input
          v-model.number="savingsRateSpreadPct"
          type="number"
          step="0.25"
          min="-5"
          max="5"
          class="rounded border border-rule bg-paper px-2 py-1 font-mono text-sm tabular-nums text-ink"
        />
      </label>
      <label class="flex flex-col gap-1 text-sm text-ink-soft">
        Mortgage spread adjustment (%)
        <input
          v-model.number="mortgageSpreadAdjustmentPct"
          type="number"
          step="0.25"
          min="-5"
          max="5"
          class="rounded border border-rule bg-paper px-2 py-1 font-mono text-sm tabular-nums text-ink"
        />
      </label>
      <label class="flex flex-col gap-1 text-sm text-ink-soft">
        Consumer spread adjustment (%)
        <input
          v-model.number="consumerSpreadAdjustmentPct"
          type="number"
          step="0.25"
          min="-5"
          max="5"
          class="rounded border border-rule bg-paper px-2 py-1 font-mono text-sm tabular-nums text-ink"
        />
      </label>
      <label class="flex flex-col gap-1 text-sm text-ink-soft">
        Business spread adjustment (%)
        <input
          v-model.number="businessSpreadAdjustmentPct"
          type="number"
          step="0.25"
          min="-5"
          max="5"
          class="rounded border border-rule bg-paper px-2 py-1 font-mono text-sm tabular-nums text-ink"
        />
      </label>
      <label class="flex flex-col gap-1 text-sm text-ink-soft">
        Target capital buffer (%)
        <input
          v-model.number="targetCapitalBufferPct"
          type="number"
          step="0.25"
          min="0"
          max="10"
          class="rounded border border-rule bg-paper px-2 py-1 font-mono text-sm tabular-nums text-ink"
        />
      </label>
      <label class="flex flex-col gap-1 text-sm text-ink-soft">
        Underwriting looseness (%)
        <input
          v-model.number="underwritingLoosenessPct"
          type="number"
          step="1"
          min="0"
          max="100"
          class="rounded border border-rule bg-paper px-2 py-1 font-mono text-sm tabular-nums text-ink"
        />
        <span class="text-xs italic text-ink-soft/80">Not yet wired to any credit-scoring effect - a placeholder lever.</span>
      </label>
    </div>

    <label class="mt-4 flex items-center gap-2 text-sm text-ink-soft">
      <input v-model="autoTapBorrowingFacility" type="checkbox" class="rounded border-rule" />
      Auto-tap central bank borrowing facility on a liquidity breach
    </label>

    <button
      type="button"
      class="mt-4 rounded border border-rule px-3 py-1.5 text-sm font-medium text-ink hover:border-ink disabled:opacity-50"
      :disabled="saving"
      @click="apply"
    >
      {{ saving ? 'Applying…' : 'Apply levers' }}
    </button>
  </section>
</template>
