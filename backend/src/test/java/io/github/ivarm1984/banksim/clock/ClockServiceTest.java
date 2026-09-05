package io.github.ivarm1984.banksim.clock;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.ivarm1984.banksim.event.DomainEventPublisher;

class ClockServiceTest {

    private static class RecordingPublisher implements DomainEventPublisher {
        final List<Object> published = new ArrayList<>();

        @Override
        public void publish(Object event) {
            published.add(event);
        }
    }

    @Test
    void tickPublishesClockTickedEventEveryTime() {
        SimulationClock clock = new SimulationClock();
        clock.setSpeed(30);
        RecordingPublisher events = new RecordingPublisher();
        ClockService service = new ClockService(clock, events);

        service.tick();

        assertThat(events.published).hasSize(1);
        assertThat(events.published.get(0)).isInstanceOf(ClockTickedEvent.class);
    }

    @Test
    void tickPublishesDayRolledOverEventOnlyWhenADateIsCrossed() {
        SimulationClock clock = new SimulationClock();
        clock.setSpeed(24 * 60 + 15);
        RecordingPublisher events = new RecordingPublisher();
        ClockService service = new ClockService(clock, events);

        service.tick();

        assertThat(events.published).hasSize(2);
        assertThat(events.published.get(1)).isInstanceOf(DayRolledOverEvent.class);
        DayRolledOverEvent dayEvent = (DayRolledOverEvent) events.published.get(1);
        assertThat(dayEvent.newDate()).isEqualTo(SimulationClock.INITIAL_TIME.toLocalDate().plusDays(1));
    }

    @Test
    void stepOneDayAdvancesNoFurtherThanTheNextDayBoundary() {
        SimulationClock clock = new SimulationClock();
        clock.setSpeed(20);
        RecordingPublisher events = new RecordingPublisher();
        ClockService service = new ClockService(clock, events);

        ClockSnapshot after = service.stepOneDay();

        assertThat(after.simulatedTime().toLocalDate()).isEqualTo(SimulationClock.INITIAL_TIME.toLocalDate().plusDays(1));
        long dayRolloverCount = events.published.stream().filter(e -> e instanceof DayRolledOverEvent).count();
        assertThat(dayRolloverCount).isEqualTo(1);
    }

    @Test
    void stepOneDayWorksEvenWhenClockIsPaused() {
        SimulationClock clock = new SimulationClock();
        clock.pause();
        RecordingPublisher events = new RecordingPublisher();
        ClockService service = new ClockService(clock, events);

        service.stepOneDay();

        assertThat(clock.state().simulatedTime()).isAfter(SimulationClock.INITIAL_TIME);
    }
}
