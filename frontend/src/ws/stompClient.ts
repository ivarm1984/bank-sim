import { Client, type IMessage } from '@stomp/stompjs'
import SockJS from 'sockjs-client'

const client = new Client({
  webSocketFactory: () => new SockJS('/ws'),
  reconnectDelay: 2000,
})

/** Re-run on every (re)connect, so subscriptions survive a dropped connection. */
const subscribers: Array<() => void> = []

client.onConnect = () => {
  subscribers.forEach((subscribe) => subscribe())
}

client.activate()

export function subscribe<T>(topic: string, onMessage: (payload: T) => void) {
  subscribers.push(() => {
    client.subscribe(topic, (message: IMessage) => onMessage(JSON.parse(message.body) as T))
  })
}
