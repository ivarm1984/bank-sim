package io.github.ivarm1984.banksim.bankhealth;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
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
    private static final BigDecimal GOOD_ROE = new BigDecimal("0.08");
    private static final int WARN = BankHealthService.WARNING_THRESHOLD_DAYS;
    private static final int TERMINAL = BankHealthService.TERMINAL_THRESHOLD_DAYS;

    private static BankHealthStatus status(int capital, int shortfall, int funding, int liquidity, LocalDate date) {
        return BankHealthService.deriveStatus(new BreachStreaks(capital, shortfall, funding, liquidity), GOOD_ROE, date);
    }

    @Test
    void healthyAndWellBeforeTheWinThresholdIsPlaying() {
        assertThat(status(0, 0, 0, 0, WELL_BEFORE_WIN)).isEqualTo(BankHealthStatus.PLAYING);
    }

    @Test
    void belowOcrUnderTheWarningThresholdIsPlaying() {
        assertThat(status(WARN - 1, 0, 0, 0, WELL_BEFORE_WIN)).isEqualTo(BankHealthStatus.PLAYING);
    }

    @Test
    void belowOcrAtTheWarningThresholdIsWarning() {
        assertThat(status(WARN, 0, 0, 0, WELL_BEFORE_WIN)).isEqualTo(BankHealthStatus.WARNING);
    }

    /** Dipping into the combined buffer is MDA territory, not failure - no matter how long it lasts. */
    @Test
    void belowOcrButAboveTscrNeverEndsTheGame() {
        assertThat(status(TERMINAL * 10, 0, 0, 0, WELL_BEFORE_WIN)).isEqualTo(BankHealthStatus.WARNING);
    }

    @Test
    void belowTscrAtTheTerminalThresholdIsGameOver() {
        assertThat(status(TERMINAL, TERMINAL, 0, 0, WELL_BEFORE_WIN)).isEqualTo(BankHealthStatus.GAME_OVER);
    }

    /** NSFR is a structural funding ratio, not a resolution trigger - it warns but never ends the game. */
    @Test
    void anNsfrBreachWarnsButNeverEndsTheGame() {
        assertThat(status(0, 0, WARN - 1, 0, WELL_BEFORE_WIN)).isEqualTo(BankHealthStatus.PLAYING);
        assertThat(status(0, 0, WARN, 0, WELL_BEFORE_WIN)).isEqualTo(BankHealthStatus.WARNING);
        assertThat(status(0, 0, TERMINAL * 10, 0, WELL_BEFORE_WIN)).isEqualTo(BankHealthStatus.WARNING);
    }

    @Test
    void liquidityStreakAtTheWarningThresholdIsWarning() {
        assertThat(status(0, 0, 0, WARN, WELL_BEFORE_WIN)).isEqualTo(BankHealthStatus.WARNING);
    }

    @Test
    void liquidityStreakAtTheTerminalThresholdIsBankRun() {
        assertThat(status(0, 0, 0, TERMINAL, WELL_BEFORE_WIN)).isEqualTo(BankHealthStatus.BANK_RUN);
    }

    @Test
    void bothTerminalStreaksFavorGameOverOverBankRun() {
        assertThat(status(TERMINAL, TERMINAL, 0, TERMINAL, WELL_BEFORE_WIN)).isEqualTo(BankHealthStatus.GAME_OVER);
    }

    @Test
    void fullyHealthyAndProfitableAtTheSurvivalThresholdWins() {
        assertThat(status(0, 0, 0, 0, AT_WIN_THRESHOLD)).isEqualTo(BankHealthStatus.WON);
    }

    @Test
    void oneDayBeforeTheSurvivalThresholdIsStillPlaying() {
        assertThat(status(0, 0, 0, 0, AT_WIN_THRESHOLD.minusDays(1))).isEqualTo(BankHealthStatus.PLAYING);
    }

    @Test
    void pastTheSurvivalThresholdButNotFullyHealthyDoesNotWin() {
        assertThat(status(1, 0, 0, 0, AT_WIN_THRESHOLD)).isEqualTo(BankHealthStatus.PLAYING);
        assertThat(status(0, 0, 1, 0, AT_WIN_THRESHOLD)).isEqualTo(BankHealthStatus.PLAYING);
        assertThat(status(0, 0, 0, 1, AT_WIN_THRESHOLD)).isEqualTo(BankHealthStatus.PLAYING);
    }

    /** Surviving by never lending isn't a win - the bank has to earn its cost of equity. */
    @Test
    void surviveWithReturnOnEquityBelowTheMinimumDoesNotWin() {
        BreachStreaks clear = BreachStreaks.NONE;
        BigDecimal justBelow = BankHealthService.WIN_MINIMUM_RETURN_ON_EQUITY.subtract(new BigDecimal("0.000001"));
        assertThat(BankHealthService.deriveStatus(clear, justBelow, AT_WIN_THRESHOLD)).isEqualTo(BankHealthStatus.PLAYING);
        assertThat(BankHealthService.deriveStatus(clear, null, AT_WIN_THRESHOLD)).isEqualTo(BankHealthStatus.PLAYING);
        assertThat(BankHealthService.deriveStatus(clear, BankHealthService.WIN_MINIMUM_RETURN_ON_EQUITY, AT_WIN_THRESHOLD))
                .isEqualTo(BankHealthStatus.WON);
    }
}
