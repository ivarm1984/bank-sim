package io.github.ivarm1984.banksim.clock;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.LongStream;

import org.springframework.stereotype.Component;

/**
 * Holds the simulation clock's state and the single piece of logic that
 * advances it. Both the real-time scheduler and a manual "step one day"
 * request go through {@link #advance()}, so they behave identically.
 */
@Component
public class SimulationClock {

    static final LocalDateTime INITIAL_TIME = LocalDateTime.of(2026, 1, 1, 0, 0);
    static final int DEFAULT_SPEED = 60;

    private final AtomicReference<ClockSnapshot> state =
            new AtomicReference<>(new ClockSnapshot(INITIAL_TIME, false, DEFAULT_SPEED));

    public ClockSnapshot state() {
        return state.get();
    }

    /** The fixed simulated date the clock starts (and resets) to — the epoch other modules anchor date arithmetic to. */
    public static LocalDate epoch() {
        return INITIAL_TIME.toLocalDate();
    }

    public ClockSnapshot play() {
        return state.updateAndGet(s -> new ClockSnapshot(s.simulatedTime(), true, s.speed()));
    }

    public ClockSnapshot pause() {
        return state.updateAndGet(s -> new ClockSnapshot(s.simulatedTime(), false, s.speed()));
    }

    public ClockSnapshot reset() {
        return state.updateAndGet(s -> new ClockSnapshot(INITIAL_TIME, false, s.speed()));
    }

    public ClockSnapshot setSpeed(int minutesPerTick) {
        if (minutesPerTick <= 0) {
            throw new IllegalArgumentException("Speed must be positive");
        }
        return state.updateAndGet(s -> new ClockSnapshot(s.simulatedTime(), s.running(), minutesPerTick));
    }

    /**
     * Advances simulated time by the current speed, regardless of whether
     * the clock is running.
     *
     * @return the calendar dates crossed while advancing, in order (empty if
     *         time didn't move far enough to cross a date)
     */
    public List<LocalDate> advance() {
        LocalDateTime before = state.get().simulatedTime();
        LocalDateTime after = state.updateAndGet(
                s -> new ClockSnapshot(s.simulatedTime().plusMinutes(s.speed()), s.running(), s.speed())).simulatedTime();
        return datesCrossed(before, after);
    }

    /** True if the clock is currently running (used by the real-time scheduler to decide whether to tick). */
    public boolean isRunning() {
        return state.get().running();
    }

    private static List<LocalDate> datesCrossed(LocalDateTime before, LocalDateTime after) {
        LocalDate fromDate = before.toLocalDate();
        LocalDate toDate = after.toLocalDate();
        long dayCount = ChronoUnit.DAYS.between(fromDate, toDate);
        return LongStream.rangeClosed(1, dayCount)
                .mapToObj(fromDate::plusDays)
                .toList();
    }
}
