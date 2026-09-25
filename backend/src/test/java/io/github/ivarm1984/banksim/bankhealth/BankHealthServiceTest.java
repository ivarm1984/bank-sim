package io.github.ivarm1984.banksim.bankhealth;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.github.ivarm1984.banksim.PostgresIntegrationTest;
import io.github.ivarm1984.banksim.treasury.TreasuryRatiosUpdatedEvent;

/**
 * Threshold/precedence logic itself is covered by the plain-unit
 * {@link BankHealthStatusTest} - this class covers the stateful parts that
 * actually need Postgres: streaks accumulating across real persisted rows,
 * and a terminal status freezing further evaluation.
 *
 * <p>{@code BankHealthRepository.findMostRecent()} looks at the single
 * globally most-recent row across this whole shared-container suite, so
 * (same reasoning as {@code TreasuryServiceTest}) each test seeds its own
 * "previous" row directly via the repository immediately before exercising
 * {@code computeAndPersist}, using a far-future date band strictly higher
 * than any other test file's dates - and the two test methods below use
 * their own increasing bands so neither can shadow the other's seed.
 */
class BankHealthServiceTest extends PostgresIntegrationTest {

    @Autowired
    private BankHealthService bankHealthService;
    @Autowired
    private BankHealthRepository bankHealthRepository;

    /** Below OCR only (into the buffer, above TSCR), no other breach. */
    private static TreasuryRatiosUpdatedEvent belowOcr(LocalDate date) {
        return new TreasuryRatiosUpdatedEvent(date, true, true, false, false, false, BigDecimal.ZERO, BigDecimal.ZERO, GOOD_ROE);
    }

    private static TreasuryRatiosUpdatedEvent healthy(LocalDate date) {
        return new TreasuryRatiosUpdatedEvent(date, false, false, false, false, false, BigDecimal.ZERO, BigDecimal.ZERO, GOOD_ROE);
    }

    private static final BigDecimal GOOD_ROE = new BigDecimal("0.08");

    @Test
    void consecutiveBreachDaysAccumulateAStreakAndAHealthyDayResetsIt() {
        LocalDate base = LocalDate.of(9100, 1, 1);
        bankHealthRepository.insert(base.minusDays(1), BankHealthStatus.PLAYING, BreachStreaks.NONE);

        BankHealthSnapshot day1 = bankHealthService.computeAndPersist(belowOcr(base));
        BankHealthSnapshot day2 = bankHealthService.computeAndPersist(belowOcr(base.plusDays(1)));
        BankHealthSnapshot day3 = bankHealthService.computeAndPersist(healthy(base.plusDays(2)));

        assertThat(day1.capitalBreachStreak()).isEqualTo(1);
        assertThat(day1.capitalShortfallStreak()).isEqualTo(0);
        assertThat(day1.status()).isEqualTo(BankHealthStatus.PLAYING);
        assertThat(day2.capitalBreachStreak()).isEqualTo(2);
        assertThat(day3.capitalBreachStreak()).isEqualTo(0);
        // Streak resets to 0 on the healthy day, and this test's far-future base
        // date (needed to stay clear of every other test file's own dates - see
        // the class Javadoc) is inherently more than WIN_SURVIVAL_YEARS past
        // SimulationClock.epoch(), so a fully healthy day here is a genuine WIN,
        // not a bug - see BankHealthStatusTest for the threshold logic itself.
        assertThat(day3.status()).isEqualTo(BankHealthStatus.WON);
    }

    @Test
    void aTerminalStatusFreezesFurtherEvaluationInsteadOfPersistingNewRows() {
        LocalDate terminalDate = LocalDate.of(9200, 1, 1);
        int terminal = BankHealthService.TERMINAL_THRESHOLD_DAYS;
        bankHealthRepository.insert(terminalDate, BankHealthStatus.GAME_OVER, new BreachStreaks(terminal, terminal, 0, 0));

        BankHealthSnapshot result = bankHealthService.computeAndPersist(new TreasuryRatiosUpdatedEvent(
                terminalDate.plusDays(1), true, true, true, true, true, BigDecimal.ZERO, BigDecimal.ZERO, null));

        assertThat(result.status()).isEqualTo(BankHealthStatus.GAME_OVER);
        assertThat(result.snapshotDate()).isEqualTo(terminalDate);
        assertThat(bankHealthService.latest().snapshotDate()).isEqualTo(terminalDate);
    }
}
