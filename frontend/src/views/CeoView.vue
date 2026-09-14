<script setup lang="ts">
import SimulationControls from '../components/SimulationControls.vue'
import PolicyLeversPanel from '../components/PolicyLeversPanel.vue'
import BankHealthPanel from '../components/BankHealthPanel.vue'
import TreasuryRatiosPanel from '../components/TreasuryRatiosPanel.vue'
import EventInjectorPanel from '../components/EventInjectorPanel.vue'
</script>

<template>
  <div class="min-h-screen bg-paper text-ink">
    <SimulationControls />

    <main class="mx-auto grid max-w-6xl gap-x-10 gap-y-10 px-6 py-8 md:grid-cols-2">
      <div class="md:col-span-2">
        <PolicyLeversPanel />
      </div>

      <BankHealthPanel />
      <EventInjectorPanel />

      <div class="md:col-span-2">
        <TreasuryRatiosPanel />
      </div>
    </main>
  </div>
</template>
