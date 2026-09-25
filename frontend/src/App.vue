<script setup lang="ts">
import { useEventFeedStore } from './stores/eventFeed'
import { subscribe } from './ws/stompClient'
import type { EventFeedMessage } from './api/types'

// App-lifetime subscription (App.vue never unmounts), so the feed/stats keep counting whichever
// view is showing.
const eventFeed = useEventFeedStore()
subscribe<EventFeedMessage>('/topic/events', eventFeed.push)
</script>

<template>
  <RouterView />
</template>
