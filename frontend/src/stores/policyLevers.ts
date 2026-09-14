import { defineStore } from 'pinia'
import { ref } from 'vue'
import * as policyLeversApi from '../api/policyLevers'
import type { PolicyLeversSnapshot } from '../api/types'

export const usePolicyLeversStore = defineStore('policyLevers', () => {
  const state = ref<PolicyLeversSnapshot | null>(null)

  async function load() {
    state.value = await policyLeversApi.fetchPolicyLevers()
  }

  async function update(snapshot: PolicyLeversSnapshot) {
    state.value = await policyLeversApi.updatePolicyLevers(snapshot)
  }

  return { state, load, update }
})
