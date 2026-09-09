package io.github.ivarm1984.banksim.bankhealth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import io.github.ivarm1984.banksim.clock.SimulationClock;

/**
 * Plain unit test (no Spring/Postgres) for {@link BankHealthService#deriveStatus} -
 * the pure win/loss state-machine logic, isolated from the shared-container
 * persistence concerns the rest of the suite has to work around (see
 * BankHealthServiceTest). Modeled on PolicyLeversTest/SimulationClockTest's
 * plain-unit-test precedent for exactly this kind of pure logic.
 */
class BankHealthStatusTest {

    private static final LocalDate WELL_BEFORE_WIN = SimulationClock.epoch().plusYears(1);
    private static final LocalDate AT_WIN_THRESHOLD = SimulationClock.epoch().plusYears(BankHealthService.WIN_SURVIVAL_YEARS);

    @Test
    void healthyAndWellBeforeTheWinThresholdIsPlaying() {
        assertThat(BankHealthService.deriveStatus(0, 0, WELL_BEFORE_WIN)).isEqualTo(BankHealthStatus.PLAYING);
    }

    @Test
    void capitalStreakBelowTheWarningThresholdIsPlaying() {
        assertThat(BankHealthService.deriveStatus(BankHealthService.WARNING_THRESHOLD_DAYS - 1, 0, WELL_BEFORE_WIN))
                .isEqualTo(BankHealthStatus.PLAYING);
    }

    @Test
    void capitalStreakAtTheWarningThresholdIsWarning() {
        assertThat(BankHealthService.deriveStatus(BankHealthService.WARNING_THRESHOLD_DAYS, 0, WELL_BEFORE_WIN))
                .isEqualTo(BankHealthStatus.WARNING);
    }

    @Test
    void capitalStreakAtTheTerminalThresholdIsGameOver() {
        assertThat(BankHealthService.deriveStatus(BankHealthService.TERMINAL_THRESHOLD_DAYS, 0, WELL_BEFORE_WIN))
                .isEqualTo(BankHealthStatus.GAME_OVER);
    }

    @Test
    void liquidityStreakAtTheWarningThresholdIsWarning() {
        assertThat(BankHealthService.deriveStatus(0, BankHealthService.WARNING_THRESHOLD_DAYS, WELL_BEFORE_WIN))
                .isEqualTo(BankHealthStatus.WARNING);
    }

    @Test
    void liquidityStreakAtTheTerminalThresholdIsBankRun() {
        assertThat(BankHealthService.deriveStatus(0, BankHealthService.TERMINAL_THRESHOLD_DAYS, WELL_BEFORE_WIN))
                .isEqualTo(BankHealthStatus.BANK_RUN);
    }

    @Test
    void bothStreaksAtTheTerminalThresholdFavorsGameOverOverBankRun() {
        assertThat(BankHealthService.deriveStatus(
                BankHealthService.TERMINAL_THRESHOLD_DAYS, BankHealthService.TERMINAL_THRESHOLD_DAYS, WELL_BEFORE_WIN))
                .isEqualTo(BankHealthStatus.GAME_OVER);
    }

    @Test
    void fullyHealthyAtTheSurvivalThresholdWins() {
        assertThat(BankHealthService.deriveStatus(0, 0, AT_WIN_THRESHOLD)).isEqualTo(BankHealthStatus.WON);
    }

    @Test
    void oneDayBeforeTheSurvivalThresholdIsStillPlaying() {
        assertThat(BankHealthService.deriveStatus(0, 0, AT_WIN_THRESHOLD.minusDays(1))).isEqualTo(BankHealthStatus.PLAYING);
    }

    @Test
    void pastTheSurvivalThresholdButNotFullyHealthyDoesNotWin() {
        assertThat(BankHealthService.deriveStatus(1, 0, AT_WIN_THRESHOLD)).isEqualTo(BankHealthStatus.PLAYING);
        assertThat(BankHealthService.deriveStatus(0, 1, AT_WIN_THRESHOLD)).isEqualTo(BankHealthStatus.PLAYING);
    }
}
