package io.github.ivarm1984.banksim.eventinjector;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.github.ivarm1984.banksim.PostgresIntegrationTest;
import io.github.ivarm1984.banksim.centralbank.CentralBankRateSchedule;
import io.github.ivarm1984.banksim.event.DomainEventPublisher;

class RateShockServiceTest extends PostgresIntegrationTest {

    @Autowired
    private RateShockRepository repository;
    @Autowired
    private DomainEventPublisher events;
    @Autowired
    private CentralBankRateSchedule schedule;

    /** base + offset as CentralBankService would report it on {@code date}. */
    private BigDecimal effectiveRate(RateShockService service, LocalDate date) {
        return CentralBankRateSchedule.clamp(schedule.ratesOn(date).policyRate().add(service.offsetAsOf(date)));
    }

    /** First roll triggers a shock (0.0 is under DAILY_PROBABILITY); every later roll is {@code directionRoll}. */
    private static Random triggersWithDirectionRoll(double directionRoll) {
        return new Random() {
            private boolean first = true;

            @Override
            public double nextDouble() {
                if (first) {
                    first = false;
                    return 0.0;
                }
                return directionRoll;
            }
        };
    }

    /** Direction roll 0.0 is under every hike probability. */
    private static Random alwaysTriggersAHike() {
        return triggersWithDirectionRoll(0.0);
    }

    /** Direction roll just under 1 is above every hike probability. */
    private static Random alwaysTriggersACut() {
        return triggersWithDirectionRoll(0.999999);
    }

    private static Random neverTriggers() {
        return new Random() {
            @Override
            public double nextDouble() {
                return 0.999999;
            }
        };
    }

    @Test
    void aForcedRollPersistsAShockAndOffsetAsOfReflectsIt() {
        RateShockService service = new RateShockService(repository, schedule, events, alwaysTriggersAHike());
        LocalDate date = LocalDate.of(2087, 3, 1);
        BigDecimal offsetDayBefore = service.offsetAsOf(date.minusDays(1));
        // Other tests share this container's rate_shocks rows, so start from a known
        // effective rate: pinned to the floor by a large cut first, whatever is accumulated.
        repository.insert(date, schedule.ratesOn(date).policyRate().add(service.offsetAsOf(date)).negate().subtract(BigDecimal.ONE));
        assertThat(effectiveRate(service, date)).isEqualByComparingTo(CentralBankRateSchedule.MIN_POLICY_RATE);

        service.maybeTriggerShock(date, false);

        assertThat(effectiveRate(service, date))
                .isEqualByComparingTo(CentralBankRateSchedule.MIN_POLICY_RATE.add(RateShockService.SHOCK_MAGNITUDE));
        assertThat(service.offsetAsOf(date.minusDays(1))).isEqualByComparingTo(offsetDayBefore);
    }

    /**
     * An offset piled up far above the cap no longer hides later cuts: the
     * cut is applied to the *effective* (clamped) rate, re-anchoring the offset.
     */
    @Test
    void aCutAfterAnOffsetPastTheCapMovesTheEffectiveRateDownImmediately() {
        LocalDate date = LocalDate.of(2086, 3, 1);
        RateShockService service = new RateShockService(repository, schedule, events, alwaysTriggersACut());
        BigDecimal base = schedule.ratesOn(date).policyRate();
        repository.insert(date, CentralBankRateSchedule.MAX_POLICY_RATE.subtract(base).subtract(service.offsetAsOf(date)).add(BigDecimal.ONE));
        assertThat(effectiveRate(service, date)).isEqualByComparingTo(CentralBankRateSchedule.MAX_POLICY_RATE);

        service.maybeTriggerShock(date, false);

        BigDecimal expected = CentralBankRateSchedule.MAX_POLICY_RATE.subtract(RateShockService.SHOCK_MAGNITUDE);
        assertThat(effectiveRate(service, date)).isEqualByComparingTo(expected);
        // Not merely clamped - base + offset is back inside the bounds.
        assertThat(schedule.ratesOn(date).policyRate().add(service.offsetAsOf(date))).isEqualByComparingTo(expected);
    }

    @Test
    void aMissedRollDoesNotPersistAnything() {
        RateShockService service = new RateShockService(repository, schedule, events, neverTriggers());
        LocalDate date = LocalDate.of(2088, 3, 1);
        BigDecimal offsetBefore = service.offsetAsOf(date);

        service.maybeTriggerShock(date, false);

        assertThat(service.offsetAsOf(date)).isEqualByComparingTo(offsetBefore);
    }

    @Test
    void offsetAsOfSumsMultipleShocksAcrossDates() {
        LocalDate day1 = LocalDate.of(2089, 1, 1);
        LocalDate day2 = LocalDate.of(2089, 1, 2);
        BigDecimal offsetBeforeDay1 = repository.sumDeltaOnOrBefore(day1);

        repository.insert(day1, new BigDecimal("0.0075"));
        repository.insert(day2, new BigDecimal("-0.0075"));

        RateShockService service = new RateShockService(repository, schedule, events, neverTriggers());
        assertThat(service.offsetAsOf(day1)).isEqualByComparingTo(offsetBeforeDay1.add(new BigDecimal("0.0075")));
        assertThat(service.offsetAsOf(day2)).isEqualByComparingTo(offsetBeforeDay1);
        assertThat(service.offsetAsOf(day1.minusDays(1))).isEqualByComparingTo(offsetBeforeDay1);

        List<RateShock> history = service.history();
        assertThat(history).extracting(RateShock::shockDate).contains(day1, day2);
    }

    /**
     * The same direction roll that hikes outside a recession cuts during one -
     * recessions bring ECB cuts, expansions lean towards hikes.
     */
    @Test
    void aRecessionBiasesTheShockTowardsACut() {
        double roll = (RateShockService.HIKE_PROBABILITY_IN_RECESSION + RateShockService.HIKE_PROBABILITY_OUTSIDE_RECESSION) / 2;
        LocalDate expansionDay = LocalDate.of(2085, 3, 1);
        LocalDate recessionDay = LocalDate.of(2085, 3, 2);
        // Pin to the middle of the corridor so neither bound can swallow the move.
        BigDecimal mid = CentralBankRateSchedule.MAX_POLICY_RATE.divide(new BigDecimal("2"));
        RateShockService probe = new RateShockService(repository, schedule, events, neverTriggers());
        repository.insert(expansionDay, mid.subtract(schedule.ratesOn(expansionDay).policyRate()).subtract(probe.offsetAsOf(expansionDay)));

        BigDecimal before = effectiveRate(probe, expansionDay);
        new RateShockService(repository, schedule, events, triggersWithDirectionRoll(roll)).maybeTriggerShock(expansionDay, false);
        assertThat(effectiveRate(probe, expansionDay)).isEqualByComparingTo(before.add(RateShockService.SHOCK_MAGNITUDE));

        BigDecimal beforeRecessionShock = effectiveRate(probe, recessionDay);
        new RateShockService(repository, schedule, events, triggersWithDirectionRoll(roll)).maybeTriggerShock(recessionDay, true);
        assertThat(effectiveRate(probe, recessionDay)).isEqualByComparingTo(beforeRecessionShock.subtract(RateShockService.SHOCK_MAGNITUDE));
    }
}
