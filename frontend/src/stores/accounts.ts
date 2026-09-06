import { defineStore } from 'pinia'
import { ref } from 'vue'
import { fetchAccountsPage } from '../api/accounts'
import type { Account } from '../api/types'

const PAGE_SIZE = 100

export const useAccountsStore = defineStore('accounts', () => {
  const list = ref<Account[]>([])
  const page = ref(0)
  // Whether there's a next page - inferred from getting a full page back, not a separate count query.
  const hasNextPage = ref(false)

  async function load() {
    list.value = await fetchAccountsPage(PAGE_SIZE, page.value * PAGE_SIZE)
    hasNextPage.value = list.value.length === PAGE_SIZE
  }

  async function nextPage() {
    if (!hasNextPage.value) return
    page.value += 1
    await load()
  }

  async function previousPage() {
    if (page.value === 0) return
    page.value -= 1
    await load()
  }

  return { list, page, hasNextPage, load, nextPage, previousPage }
})
