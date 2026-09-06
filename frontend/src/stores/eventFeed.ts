import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { EventFeedMessage } from '../api/types'

const MAX_ENTRIES = 200

export const useEventFeedStore = defineStore('eventFeed', () => {
  const entries = ref<EventFeedMessage[]>([])

  function push(message: EventFeedMessage) {
    entries.value.unshift(message)
    if (entries.value.length > MAX_ENTRIES) {
      entries.value.length = MAX_ENTRIES
    }
  }

  return { entries, push }
})
