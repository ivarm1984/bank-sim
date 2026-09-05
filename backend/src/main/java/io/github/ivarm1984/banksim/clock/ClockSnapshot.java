package io.github.ivarm1984.banksim.clock;

import java.time.LocalDateTime;

/**
 * Immutable snapshot of the simulation clock's state.
 *
 * @param simulatedTime current simulated date/time
 * @param running       whether the clock advances on each real-time tick
 * @param speed         simulated minutes advanced per tick when running
 */
public record ClockSnapshot(LocalDateTime simulatedTime, boolean running, int speed) {
}
