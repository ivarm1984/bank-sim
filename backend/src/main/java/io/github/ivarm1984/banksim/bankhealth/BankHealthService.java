package io.github.ivarm1984.banksim.bankhealth;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.NoSuchElementException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.ivarm1984.banksim.clock.SimulationClock;
import io.github.ivarm1984.banksim.event.DomainEventPublisher;
import io.github.ivarm1984.banksim.treasury.TreasuryRatiosUpdatedEvent;

/**
 * CEO-mode win/loss state machine, driven by consecutive-day breach streaks
 * off {@link TreasuryRatiosUpdatedEvent} - judged against the regulatory
 * thresholds, never the CEO's own management buffer (that only throttles
 * lending), so being more prudent can't make you lose sooner.
 *
 * <ul>
 *   <li>CAR below the overall capital requirement (OCR - into the combined
 *       buffer, MDA restrictions under CRD Art. 141), NSFR below 100%, or
 *       uncovered liquidity for {@value #WARNING_THRESHOLD_DAYS} consecutive
 *       days -> {@code WARNING}.</li>
 *   <li>CAR below the total SREP capital requirement (TSCR - failing or
 *       likely to fail, BRRD Art. 32) for {@value #TERMINAL_THRESHOLD_DAYS}
 *       consecutive days -> {@code GAME_OVER}.</li>
 *   <li>Uncovered liquidity (HQLA/reserves short and the central bank
 *       facility either switched off or out of eligible collateral) for
 *       {@value #TERMINAL_THRESHOLD_DAYS} consecutive days -> {@code BANK_RUN}.</li>
 *   <li>NSFR is a structural funding ratio, not a resolution trigger - it
 *       warns but never ends the game on its own.</li>
 *   <li>{@value #WIN_SURVIVAL_YEARS} simulated years survived with every
 *       streak at zero <em>and</em> an average annual return on equity of at
 *       least {@link #WIN_MINIMUM_RETURN_ON_EQUITY} -> {@code WON}. A bank
 *       that survives by never lending doesn't earn its cost of capital, so
 *       it doesn't win.</li>
 * </ul>
 */
@Service
public class BankHealthService {

    static final int WARNING_THRESHOLD_DAYS = 5;
    static final int TERMINAL_THRESHOLD_DAYS = 10;
    static final int WIN_SURVIVAL_YEARS = 5;
    /** Roughly the floor of what investors expect from an EU bank - below it, the bank isn't earning its cost of equity. */
    static final BigDecimal WIN_MINIMUM_RETURN_ON_EQUITY = new BigDecimal("0.05");

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

        BreachStreaks before = previous == null
                ? BreachStreaks.NONE
                : new BreachStreaks(previous.capitalBreachStreak(), previous.capitalShortfallStreak(),
                        previous.fundingBreachStreak(), previous.liquidityBreachStreak());
        BreachStreaks streaks = new BreachStreaks(
                event.capitalBelowOverallRequirement() ? before.capital() + 1 : 0,
                event.capitalBelowTotalSrepRequirement() ? before.capitalShortfall() + 1 : 0,
                event.fundingBreach() ? before.funding() + 1 : 0,
                event.liquidityBreach() ? before.liquidity() + 1 : 0);

        BankHealthStatus status = deriveStatus(streaks, event.returnOnEquity(), event.date());

        BankHealthSnapshot snapshot = repository.insert(event.date(), status, streaks);
        events.publish(new BankHealthUpdatedEvent(
                event.date(), status, streaks.capital(), streaks.capitalShortfall(), streaks.funding(), streaks.liquidity()));
        return snapshot;
    }

    /** The most recently computed snapshot - there is none until at least one simulated day's treasury batch has run. */
    public BankHealthSnapshot latest() {
        return repository.findMostRecent()
                .orElseThrow(() -> new NoSuchElementException("No bank health snapshot yet - run at least one simulated day"));
    }

    /** Package-visible for direct unit testing of the pure state-machine logic - see BankHealthStatusTest. */
    static BankHealthStatus deriveStatus(BreachStreaks streaks, BigDecimal returnOnEquity, LocalDate date) {
        if (streaks.capitalShortfall() >= TERMINAL_THRESHOLD_DAYS) {
            return BankHealthStatus.GAME_OVER;
        }
        if (streaks.liquidity() >= TERMINAL_THRESHOLD_DAYS) {
            return BankHealthStatus.BANK_RUN;
        }
        if (streaks.capital() >= WARNING_THRESHOLD_DAYS
                || streaks.funding() >= WARNING_THRESHOLD_DAYS
                || streaks.liquidity() >= WARNING_THRESHOLD_DAYS) {
            return BankHealthStatus.WARNING;
        }
        if (streaks.allClear()
                && returnOnEquity != null && returnOnEquity.compareTo(WIN_MINIMUM_RETURN_ON_EQUITY) >= 0
                && ChronoUnit.YEARS.between(SimulationClock.epoch(), date) >= WIN_SURVIVAL_YEARS) {
            return BankHealthStatus.WON;
        }
        return BankHealthStatus.PLAYING;
    }
}
