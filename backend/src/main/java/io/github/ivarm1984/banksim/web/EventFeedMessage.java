package io.github.ivarm1984.banksim.web;

import java.time.OffsetDateTime;

/** Uniform envelope for every domain event bridged to the {@code /topic/events} feed. */
public record EventFeedMessage(String type, Object payload, OffsetDateTime occurredAt) {
}
