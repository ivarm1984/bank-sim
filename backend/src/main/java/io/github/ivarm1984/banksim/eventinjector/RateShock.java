package io.github.ivarm1984.banksim.eventinjector;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** One triggered central-bank rate shock - a signed delta applied on top of the deterministic policy-rate schedule from shockDate onward. */
public record RateShock(Long id, LocalDate shockDate, BigDecimal delta, OffsetDateTime createdAt) {
}
