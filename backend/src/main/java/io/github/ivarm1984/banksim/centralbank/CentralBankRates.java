package io.github.ivarm1984.banksim.centralbank;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The simulated central bank's rate corridor as of a given simulated date.
 *
 * @param asOf                    simulated date these rates are effective for
 * @param policyRate              main policy rate (ECB main refinancing rate analog) — the base other pricing builds on
 * @param depositFacilityRate     rate paid on excess reserves parked overnight (below policy rate)
 * @param marginalLendingRate     rate charged when the bank borrows overnight to cover a shortfall (above policy rate)
 */
public record CentralBankRates(
        LocalDate asOf,
        BigDecimal policyRate,
        BigDecimal depositFacilityRate,
        BigDecimal marginalLendingRate) {
}
