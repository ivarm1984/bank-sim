package io.github.ivarm1984.banksim.interest;

import java.time.LocalDate;

/** Published once every account has had its daily interest accrual attempted for {@code date}. */
public record InterestAccrualBatchCompletedEvent(LocalDate date) {
}
