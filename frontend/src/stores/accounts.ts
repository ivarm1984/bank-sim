import { defineStore } from 'pinia'
import { ref } from 'vue'
import { fetchAccounts } from '../api/accounts'
import type { Account } from '../api/types'

export const useAccountsStore = defineStore('accounts', () => {
  const list = ref<Account[]>([])

  async function load() {
    list.value = await fetchAccounts()
  }

  return { list, load }
})
