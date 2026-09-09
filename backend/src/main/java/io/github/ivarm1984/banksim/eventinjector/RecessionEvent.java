package io.github.ivarm1984.banksim.eventinjector;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** One recession START/END audit row. {@code plannedEndDate} is set only on START rows - see RecessionShockService.isActive. */
public record RecessionEvent(Long id, RecessionEventType eventType, LocalDate eventDate, LocalDate plannedEndDate, OffsetDateTime createdAt) {
}
