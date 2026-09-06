package io.github.ivarm1984.banksim.statement;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

public record Statement(
        Long id,
        Long accountId,
        LocalDate statementDate,
        BigDecimal openingBalance,
        BigDecimal closingBalance,
        OffsetDateTime createdAt) {
}
