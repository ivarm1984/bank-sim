import { defineStore } from 'pinia'
import { ref } from 'vue'
import { fetchCustomers } from '../api/customers'
import type { Customer } from '../api/types'

export const useCustomersStore = defineStore('customers', () => {
  const list = ref<Customer[]>([])

  async function load() {
    list.value = await fetchCustomers()
  }

  return { list, load }
})
