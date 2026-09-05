package io.github.ivarm1984.banksim.agents;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Published when a {@link BillPayAgent} can't cover its bill on payday. */
public record BillPaymentFailedEvent(long accountId, BigDecimal amount, LocalDateTime simulatedNow) {
}
