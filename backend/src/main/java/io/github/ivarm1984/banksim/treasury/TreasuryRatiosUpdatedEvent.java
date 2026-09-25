package io.github.ivarm1984.banksim.treasury;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Published once a day's treasury ratio snapshot has been computed and the
 * feedback loop has reacted to it. {@code BankHealthService} judges the
 * CEO-mode warning/game-over/bank-run/win states off these flags:
 *
 * <ul>
 *   <li>{@code loanOriginationThrottled} - the CEO's own management target
 *       (OCR + buffer lever) or NSFR is breached; lending pauses. Not itself
 *       a regulatory breach.</li>
 *   <li>{@code capitalBelowOverallRequirement} - CAR below OCR (dipping into
 *       the combined buffer: MDA restrictions).</li>
 *   <li>{@code capitalBelowTotalSrepRequirement} - CAR below TSCR (failing or
 *       likely to fail).</li>
 *   <li>{@code fundingBreach} - NSFR below 100%: a structural funding
 *       problem the supervisor wants a remediation plan for, not a
 *       resolution trigger.</li>
 *   <li>{@code liquidityBreach} - the raw snapshot showed HQLA/reserve
 *       headroom below zero, whether or not {@code amountBorrowed} (capped by
 *       eligible collateral, and zero with {@code autoTapBorrowingFacility}
 *       off) covered it.</li>
 * </ul>
 * {@code amountBorrowed}/{@code amountRepaid} are zero when no action was
 * taken; {@code returnOnEquity} is null on the epoch day.
 */
public record TreasuryRatiosUpdatedEvent(
        LocalDate date,
        boolean loanOriginationThrottled,
        boolean capitalBelowOverallRequirement,
        boolean capitalBelowTotalSrepRequirement,
        boolean fundingBreach,
        boolean liquidityBreach,
        BigDecimal amountBorrowed,
        BigDecimal amountRepaid,
        BigDecimal returnOnEquity) {
}
