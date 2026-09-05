package io.github.ivarm1984.banksim.clock;

import java.time.LocalDateTime;

/** Published every time the simulation clock advances. */
public record ClockTickedEvent(LocalDateTime simulatedNow) {
}
