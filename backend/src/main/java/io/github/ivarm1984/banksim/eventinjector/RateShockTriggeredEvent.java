package io.github.ivarm1984.banksim.eventinjector;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Published when EventInjectorScheduler's daily roll triggers a surprise central-bank rate move. */
public record RateShockTriggeredEvent(LocalDate date, BigDecimal delta, BigDecimal cumulativeOffset) {
}
