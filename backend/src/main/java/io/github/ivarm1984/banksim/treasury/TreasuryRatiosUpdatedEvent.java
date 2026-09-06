package io.github.ivarm1984.banksim.treasury;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Published once a day's treasury ratio snapshot has been computed and the
 * feedback loop has reacted to it: an auto-borrow/repay against the central
 * bank facility (liquidity ratios) and/or a loan-origination throttle
 * (capital/funding ratios). {@code amountBorrowed}/{@code amountRepaid} are
 * zero when no action was taken.
 */
public record TreasuryRatiosUpdatedEvent(
        LocalDate date,
        boolean loanOriginationThrottled,
        BigDecimal amountBorrowed,
        BigDecimal amountRepaid) {
}
