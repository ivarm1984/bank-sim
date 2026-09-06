package io.github.ivarm1984.banksim.statement;

import java.time.LocalDate;

/** Published once every account has had its daily statement generated for {@code date}. */
public record StatementGenerationBatchCompletedEvent(LocalDate date) {
}
