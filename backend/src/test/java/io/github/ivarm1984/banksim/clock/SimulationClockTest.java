package io.github.ivarm1984.banksim.clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class SimulationClockTest {

    @Test
    void startsPausedAtInitialTimeWithDefaultSpeed() {
        SimulationClock clock = new SimulationClock();

        ClockSnapshot state = clock.state();

        assertThat(state.simulatedTime()).isEqualTo(SimulationClock.INITIAL_TIME);
        assertThat(state.running()).isFalse();
        assertThat(state.speed()).isEqualTo(SimulationClock.DEFAULT_SPEED);
    }

    @Test
    void advanceMovesTimeForwardBySpeedRegardlessOfRunningState() {
        SimulationClock clock = new SimulationClock();
        clock.setSpeed(30);

        clock.advance();

        assertThat(clock.state().simulatedTime()).isEqualTo(SimulationClock.INITIAL_TIME.plusMinutes(30));
    }

    @Test
    void advanceReportsEachCalendarDayCrossed() {
        SimulationClock clock = new SimulationClock();
        clock.setSpeed(24 * 60 + 30);

        var crossed = clock.advance();

        assertThat(crossed).containsExactly(SimulationClock.INITIAL_TIME.toLocalDate().plusDays(1));
    }

    @Test
    void advanceReportsNoDatesWhenStayingWithinTheSameDay() {
        SimulationClock clock = new SimulationClock();
        clock.setSpeed(30);

        assertThat(clock.advance()).isEmpty();
    }

    @Test
    void advanceReportsMultipleDatesWhenSpeedSkipsSeveralDays() {
        SimulationClock clock = new SimulationClock();
        clock.setSpeed(3 * 24 * 60);
        LocalDate start = SimulationClock.INITIAL_TIME.toLocalDate();

        var crossed = clock.advance();

        assertThat(crossed).containsExactly(start.plusDays(1), start.plusDays(2), start.plusDays(3));
    }

    @Test
    void playAndPauseToggleRunningWithoutChangingTimeOrSpeed() {
        SimulationClock clock = new SimulationClock();

        clock.play();
        assertThat(clock.isRunning()).isTrue();

        clock.pause();
        assertThat(clock.isRunning()).isFalse();
    }

    @Test
    void resetRestoresInitialTimeAndPausesButKeepsSpeed() {
        SimulationClock clock = new SimulationClock();
        clock.setSpeed(45);
        clock.play();
        clock.advance();

        ClockSnapshot afterReset = clock.reset();

        assertThat(afterReset.simulatedTime()).isEqualTo(SimulationClock.INITIAL_TIME);
        assertThat(afterReset.running()).isFalse();
        assertThat(afterReset.speed()).isEqualTo(45);
    }

    @Test
    void setSpeedRejectsNonPositiveValues() {
        SimulationClock clock = new SimulationClock();

        assertThatThrownBy(() -> clock.setSpeed(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> clock.setSpeed(-5)).isInstanceOf(IllegalArgumentException.class);
    }
}
