package io.github.ivarm1984.banksim.centralbank;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.github.ivarm1984.banksim.PostgresIntegrationTest;
import io.github.ivarm1984.banksim.clock.ClockService;
import io.github.ivarm1984.banksim.clock.SimulationClock;
import io.github.ivarm1984.banksim.eventinjector.RateShockRepository;

/**
 * {@code RateShockRepository} is a real, persisted, append-only log shared
 * across the whole Testcontainers-backed suite (including whatever the
 * random {@code EventInjectorScheduler} roll fires during other tests'
 * {@code clockService.stepOneDay()} calls, and this class's own other test
 * methods, since {@code clockService.reset()} always returns to the same
 * epoch date). Every assertion below is computed relative to whatever is
 * already accumulated at that date rather than assuming a clean slate, so
 * the tests stay correct regardless of execution order - same hygiene
 * {@code TreasuryServiceTest} applies to its own shared-DB state.
 */
class CentralBankServiceTest extends PostgresIntegrationTest {

    @Autowired
    private ClockService clockService;
    @Autowired
    private CentralBankRateSchedule schedule;
    @Autowired
    private CentralBankService centralBankService;
    @Autowired
    private RateShockRepository rateShockRepository;

    @Test
    void currentRatesReflectTheBaseScheduleAndAnyAccumulatedShockOffset() {
        clockService.reset();
        LocalDate today = clockService.state().simulatedTime().toLocalDate();

        BigDecimal accumulatedOffset = rateShockRepository.sumDeltaOnOrBefore(today);
        BigDecimal expected = CentralBankRateSchedule.clamp(schedule.ratesOn(today).policyRate().add(accumulatedOffset));

        assertThat(centralBankService.currentRates().policyRate()).isEqualByComparingTo(expected);
    }

    @Test
    void aNewShockShiftsCurrentRatesByExactlyItsDelta() {
        clockService.reset();
        LocalDate today = clockService.state().simulatedTime().toLocalDate();
        BigDecimal offsetBefore = rateShockRepository.sumDeltaOnOrBefore(today);
        BigDecimal delta = new BigDecimal("0.0075");

        rateShockRepository.insert(today, delta);

        BigDecimal expectedPolicyRate = CentralBankRateSchedule.clamp(schedule.ratesOn(today).policyRate().add(offsetBefore).add(delta));
        CentralBankRates shocked = centralBankService.currentRates();
        assertThat(shocked.policyRate()).isEqualByComparingTo(expectedPolicyRate);
        assertThat(shocked.depositFacilityRate()).isEqualByComparingTo(shocked.policyRate().subtract(CentralBankRateSchedule.CORRIDOR_WIDTH));
        assertThat(shocked.marginalLendingRate()).isEqualByComparingTo(shocked.policyRate().add(CentralBankRateSchedule.CORRIDOR_WIDTH));
    }

    @Test
    void shockedRateIsClampedToTheUpperBound() {
        clockService.reset();
        LocalDate today = clockService.state().simulatedTime().toLocalDate();
        BigDecimal offsetBefore = rateShockRepository.sumDeltaOnOrBefore(today);
        BigDecimal base = schedule.ratesOn(today).policyRate();

        // Enough to push comfortably past MAX_POLICY_RATE regardless of whatever is already accumulated.
        BigDecimal delta = CentralBankRateSchedule.MAX_POLICY_RATE.subtract(base).subtract(offsetBefore).add(BigDecimal.ONE);
        rateShockRepository.insert(today, delta);

        assertThat(centralBankService.currentRates().policyRate()).isEqualByComparingTo(CentralBankRateSchedule.MAX_POLICY_RATE);
    }

    @Test
    void shockedRateIsClampedToTheLowerBound() {
        clockService.reset();
        LocalDate today = clockService.state().simulatedTime().toLocalDate();
        BigDecimal offsetBefore = rateShockRepository.sumDeltaOnOrBefore(today);
        BigDecimal base = schedule.ratesOn(today).policyRate();

        // Enough to push comfortably below MIN_POLICY_RATE regardless of whatever is already accumulated.
        BigDecimal delta = CentralBankRateSchedule.MIN_POLICY_RATE.subtract(base).subtract(offsetBefore).subtract(BigDecimal.ONE);
        rateShockRepository.insert(today, delta);

        assertThat(centralBankService.currentRates().policyRate()).isEqualByComparingTo(CentralBankRateSchedule.MIN_POLICY_RATE);
    }
}
