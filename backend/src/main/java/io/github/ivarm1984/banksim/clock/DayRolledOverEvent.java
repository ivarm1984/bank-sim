package io.github.ivarm1984.banksim.clock;

import java.time.LocalDate;

/**
 * Published once per simulated calendar day crossed while the clock advances.
 *
 * <p>Synchronous listeners run in an explicit {@code @Order} (lowest first)
 * so the day's results never depend on bean registration order:
 * <ol>
 *   <li>{@link #ORDER_EVENT_INJECTOR} - rate shock / recession / loan phase
 *       rolls, so a day-D rate shock already applies to day-D accrual;</li>
 *   <li>{@link #ORDER_TREASURY_BORROWING_INTEREST} - central bank facility
 *       interest, so it's in the ledger before the day's ratio snapshot;</li>
 *   <li>{@link #ORDER_INTEREST_ACCRUAL} - customer interest accrual, which
 *       then synchronously chains statements -> treasury snapshot -> bank
 *       health, so it must run last.</li>
 * </ol>
 */
public record DayRolledOverEvent(LocalDate newDate) {

    public static final int ORDER_EVENT_INJECTOR = 10;
    public static final int ORDER_TREASURY_BORROWING_INTEREST = 20;
    public static final int ORDER_INTEREST_ACCRUAL = 30;
}
