package io.github.ivarm1984.banksim.clock;

import java.time.LocalDate;

/** Published once per simulated calendar day crossed while the clock advances. */
public record DayRolledOverEvent(LocalDate newDate) {
}
