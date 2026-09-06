package io.github.ivarm1984.banksim.centralbank;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import io.github.ivarm1984.banksim.clock.SimulationClock;

class CentralBankRateScheduleTest {

    private final CentralBankRateSchedule schedule = new CentralBankRateSchedule();

    @Test
    void policyRateAtEpochIsTheInitialRate() {
        BigDecimal rate = schedule.policyRateOn(SimulationClock.epoch());

        assertThat(rate).isEqualByComparingTo(CentralBankRateSchedule.INITIAL_POLICY_RATE);
    }

    @Test
    void policyRateIsStableWithinAReviewPeriod() {
        LocalDate midPeriod = SimulationClock.epoch().plusDays(10);

        assertThat(schedule.policyRateOn(midPeriod)).isEqualByComparingTo(schedule.policyRateOn(midPeriod));
        assertThat(schedule.policyRateOn(midPeriod))
                .isEqualByComparingTo(schedule.policyRateOn(SimulationClock.epoch().plusDays(20)));
    }

    @Test
    void policyRateIsDeterministicAcrossRepeatedQueries() {
        LocalDate farOut = SimulationClock.epoch().plusYears(3).plusDays(17);

        BigDecimal first = schedule.policyRateOn(farOut);
        BigDecimal second = schedule.policyRateOn(farOut);

        assertThat(first).isEqualByComparingTo(second);
    }

    @Test
    void policyRateStaysWithinBoundsOverManyYears() {
        LocalDate date = SimulationClock.epoch();
        for (int i = 0; i < 200; i++) {
            date = date.plusDays(CentralBankRateSchedule.REVIEW_PERIOD_DAYS);
            BigDecimal rate = schedule.policyRateOn(date);

            assertThat(rate).isGreaterThanOrEqualTo(CentralBankRateSchedule.MIN_POLICY_RATE);
            assertThat(rate).isLessThanOrEqualTo(CentralBankRateSchedule.MAX_POLICY_RATE);
        }
    }

    @Test
    void policyRateActuallyMovesOverManyReviews() {
        // Guards against a real bug this schedule once had: re-seeding a
        // fresh Random per review index (SEED + reviewIndex) looked
        // deterministic and stayed in-bounds, but java.util.Random's first
        // draw from consecutive seeds is strongly correlated, so every
        // review silently landed in the "hold" bucket - a flat rate for
        // 15+ simulated years. Assert the walk isn't degenerate.
        long distinctRates = java.util.stream.IntStream.range(0, 60)
                .mapToObj(i -> schedule.policyRateOn(SimulationClock.epoch().plusDays((long) (i + 1) * CentralBankRateSchedule.REVIEW_PERIOD_DAYS)))
                .distinct()
                .count();

        assertThat(distinctRates).isGreaterThan(1);
    }

    @Test
    void depositAndMarginalRatesSitInAFixedCorridorAroundPolicyRate() {
        LocalDate date = SimulationClock.epoch().plusDays(100);

        CentralBankRates rates = schedule.ratesOn(date);

        assertThat(rates.asOf()).isEqualTo(date);
        assertThat(rates.depositFacilityRate())
                .isEqualByComparingTo(rates.policyRate().subtract(CentralBankRateSchedule.CORRIDOR_WIDTH));
        assertThat(rates.marginalLendingRate())
                .isEqualByComparingTo(rates.policyRate().add(CentralBankRateSchedule.CORRIDOR_WIDTH));
    }
}
