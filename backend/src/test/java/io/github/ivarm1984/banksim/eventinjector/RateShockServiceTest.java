package io.github.ivarm1984.banksim.eventinjector;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.github.ivarm1984.banksim.PostgresIntegrationTest;
import io.github.ivarm1984.banksim.event.DomainEventPublisher;

class RateShockServiceTest extends PostgresIntegrationTest {

    @Autowired
    private RateShockRepository repository;
    @Autowired
    private DomainEventPublisher events;

    /** Rolls succeed (below any probability) and always picks the "hike" branch (nextBoolean true). */
    private static Random alwaysTriggersAHike() {
        return new Random() {
            @Override
            public double nextDouble() {
                return 0.0;
            }

            @Override
            public boolean nextBoolean() {
                return true;
            }
        };
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
        RateShockService service = new RateShockService(repository, events, alwaysTriggersAHike());
        LocalDate date = LocalDate.of(2087, 3, 1);
        BigDecimal offsetBefore = service.offsetAsOf(date);

        service.maybeTriggerShock(date);

        assertThat(service.offsetAsOf(date)).isEqualByComparingTo(offsetBefore.add(RateShockService.SHOCK_MAGNITUDE));
        assertThat(service.offsetAsOf(date.minusDays(1))).isEqualByComparingTo(offsetBefore);
    }

    @Test
    void aMissedRollDoesNotPersistAnything() {
        RateShockService service = new RateShockService(repository, events, neverTriggers());
        LocalDate date = LocalDate.of(2088, 3, 1);
        BigDecimal offsetBefore = service.offsetAsOf(date);

        service.maybeTriggerShock(date);

        assertThat(service.offsetAsOf(date)).isEqualByComparingTo(offsetBefore);
    }

    @Test
    void offsetAsOfSumsMultipleShocksAcrossDates() {
        LocalDate day1 = LocalDate.of(2089, 1, 1);
        LocalDate day2 = LocalDate.of(2089, 1, 2);
        BigDecimal offsetBeforeDay1 = repository.sumDeltaOnOrBefore(day1);

        repository.insert(day1, new BigDecimal("0.0075"));
        repository.insert(day2, new BigDecimal("-0.0075"));

        RateShockService service = new RateShockService(repository, events, neverTriggers());
        assertThat(service.offsetAsOf(day1)).isEqualByComparingTo(offsetBeforeDay1.add(new BigDecimal("0.0075")));
        assertThat(service.offsetAsOf(day2)).isEqualByComparingTo(offsetBeforeDay1);
        assertThat(service.offsetAsOf(day1.minusDays(1))).isEqualByComparingTo(offsetBeforeDay1);

        List<RateShock> history = service.history();
        assertThat(history).extracting(RateShock::shockDate).contains(day1, day2);
    }
}
