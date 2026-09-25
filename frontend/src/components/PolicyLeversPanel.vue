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
// Risk appetite 0-1, maps linearly onto the highest borrower PD still approved (2% at 0, 30% at 1) -
// shown as that PD cutoff since that's the decision it really is.
const MIN_APPROVAL_PD_PCT = 2
const MAX_APPROVAL_PD_PCT = 30
const maxApprovalPdPct = ref(16)
const autoTapBorrowingFacility = ref(true)
const saving = ref(false)

// Rounded both ways so binary float noise never shows up as e.g. 7.000000000000001 - lever
// values are only ever meaningful to a basis point (0.01%).
const toPct = (fraction: number) => Math.round(fraction * 10000) / 100
const toFraction = (pct: number) => Math.round(pct * 100) / 10000

function syncDraftFromState() {
  const s = policyLevers.state
  if (!s) return
  savingsRateSpreadPct.value = toPct(s.savingsRateSpread)
  mortgageSpreadAdjustmentPct.value = toPct(s.mortgageSpreadAdjustment)
  consumerSpreadAdjustmentPct.value = toPct(s.consumerSpreadAdjustment)
  businessSpreadAdjustmentPct.value = toPct(s.businessSpreadAdjustment)
  targetCapitalBufferPct.value = toPct(s.targetCapitalBuffer)
  maxApprovalPdPct.value =
    Math.round((MIN_APPROVAL_PD_PCT + (MAX_APPROVAL_PD_PCT - MIN_APPROVAL_PD_PCT) * s.underwritingLooseness) * 100) / 100
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
      savingsRateSpread: toFraction(savingsRateSpreadPct.value),
      mortgageSpreadAdjustment: toFraction(mortgageSpreadAdjustmentPct.value),
      consumerSpreadAdjustment: toFraction(consumerSpreadAdjustmentPct.value),
      businessSpreadAdjustment: toFraction(businessSpreadAdjustmentPct.value),
      targetCapitalBuffer: toFraction(targetCapitalBufferPct.value),
      underwritingLooseness: Math.min(
        1,
        Math.max(
          0,
          Math.round(((maxApprovalPdPct.value - MIN_APPROVAL_PD_PCT) / (MAX_APPROVAL_PD_PCT - MIN_APPROVAL_PD_PCT)) * 10000) /
            10000,
        ),
      ),
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
        Management buffer above 12.5% OCR (%)
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
        Approval cutoff: max borrower PD (%)
        <input
          v-model.number="maxApprovalPdPct"
          type="number"
          step="1"
          :min="MIN_APPROVAL_PD_PCT"
          :max="MAX_APPROVAL_PD_PCT"
          class="rounded border border-rule bg-paper px-2 py-1 font-mono text-sm tabular-nums text-ink"
        />
        <span class="text-xs">Applicants riskier than this are declined. Loans above 60% DSTI are always declined.</span>
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
