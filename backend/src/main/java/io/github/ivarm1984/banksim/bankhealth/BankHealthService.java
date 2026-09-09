package io.github.ivarm1984.banksim.bankhealth;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.NoSuchElementException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.ivarm1984.banksim.clock.SimulationClock;
import io.github.ivarm1984.banksim.event.DomainEventPublisher;
import io.github.ivarm1984.banksim.treasury.TreasuryRatiosUpdatedEvent;

/**
 * CEO-mode win/loss state machine, driven entirely by consecutive-day breach
 * streaks off {@link TreasuryRatiosUpdatedEvent} - deliberately simple (no
 * separate "episode" bookkeeping, no new borrowing-cap concept) so the rules
 * stay legible: two independent streak counters, each with a warning
 * threshold and a terminal threshold.
 *
 * <ul>
 *   <li>Capital breach (CAR/NSFR below minimum, i.e.
 *       {@code loanOriginationThrottled}) for {@value #WARNING_THRESHOLD_DAYS}
 *       consecutive days -> {@code WARNING}; the same streak reaching
 *       {@value #TERMINAL_THRESHOLD_DAYS} days -> {@code GAME_OVER}.</li>
 *   <li>Liquidity breach ({@code liquidityBreach}, uncorrected because the
 *       CEO's {@code autoTapBorrowingFacility} lever is off - with it on the
 *       feedback loop always fully repairs the ratio the same day) for
 *       {@value #TERMINAL_THRESHOLD_DAYS} consecutive days -> {@code
 *       BANK_RUN}.</li>
 *   <li>{@value #WIN_SURVIVAL_YEARS} simulated years survived since
 *       {@link SimulationClock#epoch()} with both streaks at zero (fully
 *       healthy) -> {@code WON}.</li>
 * </ul>
 */
@Service
public class BankHealthService {

    static final int WARNING_THRESHOLD_DAYS = 5;
    static final int TERMINAL_THRESHOLD_DAYS = 10;
    static final int WIN_SURVIVAL_YEARS = 5;

    private final BankHealthRepository repository;
    private final DomainEventPublisher events;

    public BankHealthService(BankHealthRepository repository, DomainEventPublisher events) {
        this.repository = repository;
        this.events = events;
    }

    /**
     * Recomputes the breach streaks and derives today's status. Once a
     * terminal status has been reached the game has ended - this becomes a
     * no-op, returning the frozen terminal snapshot unchanged rather than
     * inserting further rows or publishing further events.
     */
    @Transactional
    public BankHealthSnapshot computeAndPersist(TreasuryRatiosUpdatedEvent event) {
        BankHealthSnapshot previous = repository.findMostRecent().orElse(null);
        if (previous != null && previous.status().isTerminal()) {
            return previous;
        }

        int previousCapitalStreak = previous == null ? 0 : previous.capitalBreachStreak();
        int previousLiquidityStreak = previous == null ? 0 : previous.liquidityBreachStreak();
        int capitalStreak = event.loanOriginationThrottled() ? previousCapitalStreak + 1 : 0;
        int liquidityStreak = event.liquidityBreach() ? previousLiquidityStreak + 1 : 0;

        BankHealthStatus status = deriveStatus(capitalStreak, liquidityStreak, event.date());

        BankHealthSnapshot snapshot = repository.insert(event.date(), status, capitalStreak, liquidityStreak);
        events.publish(new BankHealthUpdatedEvent(event.date(), status, capitalStreak, liquidityStreak));
        return snapshot;
    }

    /** The most recently computed snapshot - there is none until at least one simulated day's treasury batch has run. */
    public BankHealthSnapshot latest() {
        return repository.findMostRecent()
                .orElseThrow(() -> new NoSuchElementException("No bank health snapshot yet - run at least one simulated day"));
    }

    /** Package-visible for direct unit testing of the pure state-machine logic - see BankHealthStatusTest. */
    static BankHealthStatus deriveStatus(int capitalStreak, int liquidityStreak, LocalDate date) {
        if (capitalStreak >= TERMINAL_THRESHOLD_DAYS) {
            return BankHealthStatus.GAME_OVER;
        }
        if (liquidityStreak >= TERMINAL_THRESHOLD_DAYS) {
            return BankHealthStatus.BANK_RUN;
        }
        if (capitalStreak >= WARNING_THRESHOLD_DAYS || liquidityStreak >= WARNING_THRESHOLD_DAYS) {
            return BankHealthStatus.WARNING;
        }
        if (capitalStreak == 0 && liquidityStreak == 0
                && ChronoUnit.YEARS.between(SimulationClock.epoch(), date) >= WIN_SURVIVAL_YEARS) {
            return BankHealthStatus.WON;
        }
        return BankHealthStatus.PLAYING;
    }
}
