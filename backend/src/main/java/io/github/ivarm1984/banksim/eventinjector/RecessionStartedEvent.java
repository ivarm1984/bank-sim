package io.github.ivarm1984.banksim.eventinjector;

import java.time.LocalDate;

/** Published when EventInjectorScheduler's daily roll starts a new recession window. */
public record RecessionStartedEvent(LocalDate date, LocalDate plannedEndDate) {
}
