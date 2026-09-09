package io.github.ivarm1984.banksim.eventinjector;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.github.ivarm1984.banksim.PostgresIntegrationTest;
import io.github.ivarm1984.banksim.event.DomainEventPublisher;

class RecessionShockServiceTest extends PostgresIntegrationTest {

    @Autowired
    private RecessionEventRepository repository;
    @Autowired
    private DomainEventPublisher events;

    private static java.util.Random alwaysTriggers() {
        return new java.util.Random() {
            @Override
            public double nextDouble() {
                return 0.0;
            }

            @Override
            public int nextInt(int bound) {
                return 0;
            }
        };
    }

    private static java.util.Random neverTriggers() {
        return new java.util.Random() {
            @Override
            public double nextDouble() {
                return 0.999999;
            }
        };
    }

    @Test
    void isActiveIsFalseBeforeAnyRecessionHasEverStarted() {
        RecessionShockService service = new RecessionShockService(repository, events, neverTriggers());
        LocalDate date = LocalDate.of(2090, 1, 1);

        assertThat(service.isActive(date)).isFalse();
    }

    @Test
    void aForcedTickStartsARecessionActiveImmediatelyAndUntilItsPlannedEnd() {
        RecessionShockService service = new RecessionShockService(repository, events, alwaysTriggers());
        LocalDate start = LocalDate.of(2091, 1, 1);

        boolean active = service.tick(start);

        assertThat(active).isTrue();
        assertThat(service.isActive(start)).isTrue();
        assertThat(service.isActive(start.plusMonths(RecessionShockService.MIN_DURATION_MONTHS).minusDays(1))).isTrue();
        assertThat(service.isActive(start.plusMonths(RecessionShockService.MIN_DURATION_MONTHS))).isFalse();
        assertThat(service.isActive(start.minusDays(1))).isFalse();
    }

    @Test
    void tickWritesExactlyOneEndRowTheDayTheWindowLapses() {
        RecessionShockService service = new RecessionShockService(repository, events, alwaysTriggers());
        LocalDate start = LocalDate.of(2092, 1, 1);
        service.tick(start);
        LocalDate plannedEnd = start.plusMonths(RecessionShockService.MIN_DURATION_MONTHS);

        // neverTriggers so the lapse-detection branch doesn't immediately start a brand-new recession too.
        RecessionShockService noRestart = new RecessionShockService(repository, events, neverTriggers());
        boolean stillActiveOneDayBeforeEnd = noRestart.tick(plannedEnd.minusDays(1));
        boolean activeOnLapseDay = noRestart.tick(plannedEnd);
        boolean activeOneDayAfterCallingTickAgain = noRestart.tick(plannedEnd);

        assertThat(stillActiveOneDayBeforeEnd).isTrue();
        assertThat(activeOnLapseDay).isFalse();
        assertThat(activeOneDayAfterCallingTickAgain).isFalse();
        long endRowCount = repository.findAll().stream()
                .filter(e -> e.eventType() == RecessionEventType.END && e.eventDate().equals(plannedEnd))
                .count();
        assertThat(endRowCount).isEqualTo(1);
    }
}
