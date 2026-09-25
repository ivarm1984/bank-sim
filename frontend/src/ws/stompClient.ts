import { Client, type IMessage, type StompSubscription } from '@stomp/stompjs'
import SockJS from 'sockjs-client'

const client = new Client({
  webSocketFactory: () => new SockJS('/ws'),
  reconnectDelay: 2000,
})

/** Re-run on every (re)connect, so subscriptions survive a dropped connection. */
const subscribers = new Set<() => void>()

client.onConnect = () => {
  subscribers.forEach((attach) => attach())
}

client.activate()

/**
 * Subscribes now if the socket is already connected (e.g. a view mounted after client-side
 * navigation), and again on every reconnect. Returns an unsubscribe function - call it from
 * `onUnmounted`, or every past mount's handler piles up as a duplicate after the next reconnect.
 */
export function subscribe<T>(topic: string, onMessage: (payload: T) => void): () => void {
  let subscription: StompSubscription | null = null
  const attach = () => {
    subscription = client.subscribe(topic, (message: IMessage) => onMessage(JSON.parse(message.body) as T))
  }
  subscribers.add(attach)
  if (client.connected) attach()

  return () => {
    subscribers.delete(attach)
    // After a dropped connection the old subscription is already gone server-side - only
    // unsubscribe over a live socket.
    if (subscription && client.connected) subscription.unsubscribe()
    subscription = null
  }
}
