package io.github.ivarm1984.banksim.web;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import io.github.ivarm1984.banksim.clock.ClockService;
import io.github.ivarm1984.banksim.clock.ClockTickedEvent;

/** Bridges the clock's state onto {@code /topic/clock} on every tick, kept separate from the event feed. */
@Component
public class ClockStatePublisher {

    private final ClockService clockService;
    private final SimpMessagingTemplate messagingTemplate;

    public ClockStatePublisher(ClockService clockService, SimpMessagingTemplate messagingTemplate) {
        this.clockService = clockService;
        this.messagingTemplate = messagingTemplate;
    }

    @Async
    @TransactionalEventListener(fallbackExecution = true)
    public void onClockTicked(ClockTickedEvent event) {
        messagingTemplate.convertAndSend("/topic/clock", clockService.state());
    }
}
