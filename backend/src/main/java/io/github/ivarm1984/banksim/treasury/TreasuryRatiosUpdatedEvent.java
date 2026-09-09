package io.github.ivarm1984.banksim.treasury;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Published once a day's treasury ratio snapshot has been computed and the
 * feedback loop has reacted to it: an auto-borrow/repay against the central
 * bank facility (liquidity ratios) and/or a loan-origination throttle
 * (capital/funding ratios). {@code amountBorrowed}/{@code amountRepaid} are
 * zero when no action was taken. {@code liquidityBreach} is true whenever
 * the raw snapshot showed HQLA/reserve headroom below zero, regardless of
 * whether {@code amountBorrowed} actually corrected it (it won't, if
 * {@code autoTapBorrowingFacility} is off) - see {@code BankHealthService},
 * which watches consecutive-day breach streaks for the CEO-mode
 * warning/game-over/bank-run states.
 */
public record TreasuryRatiosUpdatedEvent(
        LocalDate date,
        boolean loanOriginationThrottled,
        boolean liquidityBreach,
        BigDecimal amountBorrowed,
        BigDecimal amountRepaid) {
}
