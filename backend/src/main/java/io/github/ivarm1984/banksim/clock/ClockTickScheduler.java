package io.github.ivarm1984.banksim.clock;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Drives the simulation clock forward in real time, once per second, while it's running. */
@Component
public class ClockTickScheduler {

    private final SimulationClock clock;
    private final ClockService clockService;

    public ClockTickScheduler(SimulationClock clock, ClockService clockService) {
        this.clock = clock;
        this.clockService = clockService;
    }

    @Scheduled(fixedRate = 1000)
    public void tick() {
        if (clock.isRunning()) {
            clockService.tick();
        }
    }
}
