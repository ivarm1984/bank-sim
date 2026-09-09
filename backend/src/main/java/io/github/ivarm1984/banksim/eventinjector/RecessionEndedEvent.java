package io.github.ivarm1984.banksim.eventinjector;

import java.time.LocalDate;

/** Published the day an active recession window lapses. */
public record RecessionEndedEvent(LocalDate date) {
}
