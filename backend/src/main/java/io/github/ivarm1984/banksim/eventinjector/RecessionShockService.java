package io.github.ivarm1984.banksim.eventinjector;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.ivarm1984.banksim.event.DomainEventPublisher;

/**
 * Random recession windows - a temporary elevated-risk period. Read (via
 * {@link #isActive}) by borrower agents, whose payment-difficulty rate rises,
 * and by the IFRS 9 ECL calculation ({@code loan.CreditRisk}), whose
 * forward-looking PDs rise with it. State is a
 * persisted start/end event log (see {@link RecessionEventRepository}), not
 * a boolean flag, so "active as of a date" stays a pure derived read.
 */
@Service
public class RecessionShockService {

    private static final Logger log = LoggerFactory.getLogger(RecessionShockService.class);

    /** ~once every ~3.4 sim-years - a rare macro event. */
    static final double START_DAILY_PROBABILITY = 0.0008;
    static final int MIN_DURATION_MONTHS = 6;
    static final int MAX_DURATION_MONTHS = 18;

    private final RecessionEventRepository repository;
    private final DomainEventPublisher events;
    private final Random random;

    @Autowired
    public RecessionShockService(RecessionEventRepository repository, DomainEventPublisher events) {
        this(repository, events, new Random());
    }

    /** Test seam - lets tests force/deny a recession start deterministically. */
    RecessionShockService(RecessionEventRepository repository, DomainEventPublisher events, Random random) {
        this.repository = repository;
        this.events = events;
        this.random = random;
    }

    /** The latest event on/before {@code asOfDate}, if it's a START not yet followed by an END row. */
    private Optional<RecessionEvent> activeStartAsOf(LocalDate asOfDate) {
        return repository.findMostRecentOnOrBefore(asOfDate).filter(e -> e.eventType() == RecessionEventType.START);
    }

    /** Pure read: is a recession window active as of {@code asOfDate}? */
    public boolean isActive(LocalDate asOfDate) {
        return activeStartAsOf(asOfDate).map(e -> asOfDate.isBefore(e.plannedEndDate())).orElse(false);
    }

    /** Writes the END row the day an active window lapses, then rolls for a new START. Returns the resulting active state. */
    @Transactional
    public boolean tick(LocalDate date) {
        boolean active = isActive(date);
        Optional<RecessionEvent> latestStart = activeStartAsOf(date);
        if (latestStart.isPresent() && !date.isBefore(latestStart.get().plannedEndDate())) {
            repository.insertEnd(date);
            log.info("Recession ended on {}", date);
            events.publish(new RecessionEndedEvent(date));
            active = false;
        }
        if (!active && random.nextDouble() < START_DAILY_PROBABILITY) {
            int durationMonths = MIN_DURATION_MONTHS + random.nextInt(MAX_DURATION_MONTHS - MIN_DURATION_MONTHS + 1);
            LocalDate plannedEnd = date.plusMonths(durationMonths);
            repository.insertStart(date, plannedEnd);
            log.info("Recession started on {}, planned to end {}", date, plannedEnd);
            events.publish(new RecessionStartedEvent(date, plannedEnd));
            active = true;
        }
        return active;
    }

    public List<RecessionEvent> history() {
        return repository.findAll();
    }
}
