import { defineStore } from 'pinia'
import { ref } from 'vue'
import * as clockApi from '../api/clock'
import type { ClockSnapshot } from '../api/types'

export const useClockStore = defineStore('clock', () => {
  const state = ref<ClockSnapshot | null>(null)

  async function load() {
    state.value = await clockApi.fetchClockState()
  }

  async function play() {
    state.value = await clockApi.play()
  }

  async function pause() {
    state.value = await clockApi.pause()
  }

  async function reset() {
    state.value = await clockApi.reset()
  }

  async function stepDay() {
    state.value = await clockApi.stepDay()
  }

  async function setSpeed(minutesPerTick: number) {
    state.value = await clockApi.setSpeed(minutesPerTick)
  }

  /** Applied from the /topic/clock WebSocket feed on every simulated tick. */
  function patch(snapshot: ClockSnapshot) {
    state.value = snapshot
  }

  return { state, load, play, pause, reset, stepDay, setSpeed, patch }
})
