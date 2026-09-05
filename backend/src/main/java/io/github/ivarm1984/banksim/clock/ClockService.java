package io.github.ivarm1984.banksim.clock;

import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Service;

import io.github.ivarm1984.banksim.event.DomainEventPublisher;

/**
 * Facade over {@link SimulationClock}: the one place that advances the
 * clock and publishes the resulting events, so the real-time scheduler and
 * a manual "step one day" request can't drift apart.
 */
@Service
public class ClockService {

    private final SimulationClock clock;
    private final DomainEventPublisher events;

    public ClockService(SimulationClock clock, DomainEventPublisher events) {
        this.clock = clock;
        this.events = events;
    }

    public ClockSnapshot state() {
        return clock.state();
    }

    public ClockSnapshot play() {
        return clock.play();
    }

    public ClockSnapshot pause() {
        return clock.pause();
    }

    public ClockSnapshot reset() {
        return clock.reset();
    }

    public ClockSnapshot setSpeed(int minutesPerTick) {
        return clock.setSpeed(minutesPerTick);
    }

    /** Advances by one tick's worth of simulated time and publishes the resulting events. */
    public void tick() {
        advanceAndPublish();
    }

    /**
     * Advances repeatedly, with no real-time delay, until at least one
     * simulated day boundary has been crossed - regardless of play/pause
     * state or configured speed.
     */
    public ClockSnapshot stepOneDay() {
        List<LocalDate> crossed;
        do {
            crossed = advanceAndPublish();
        } while (crossed.isEmpty());
        return clock.state();
    }

    private List<LocalDate> advanceAndPublish() {
        List<LocalDate> datesCrossed = clock.advance();
        events.publish(new ClockTickedEvent(clock.state().simulatedTime()));
        datesCrossed.forEach(date -> events.publish(new DayRolledOverEvent(date)));
        return datesCrossed;
    }
}
