package io.github.ivarm1984.banksim.eventinjector;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.ivarm1984.banksim.event.DomainEventPublisher;

/**
 * Random central-bank rate shocks - a persisted, append-only log of signed
 * deltas (see {@link RateShockRepository}), so the deterministic
 * {@code CentralBankRateSchedule} base rate stays a pure function of date;
 * the shock is layered on top in {@code CentralBankService.currentRates()}.
 */
@Service
public class RateShockService {

    private static final Logger log = LoggerFactory.getLogger(RateShockService.class);

    /** ~once every ~333 sim-days on average. */
    static final double DAILY_PROBABILITY = 0.003;
    /** 75bps - a "surprise" move, 3x the routine 25bps review step CentralBankRateSchedule uses. */
    static final BigDecimal SHOCK_MAGNITUDE = new BigDecimal("0.0075");

    private final RateShockRepository repository;
    private final DomainEventPublisher events;
    private final Random random;

    @Autowired
    public RateShockService(RateShockRepository repository, DomainEventPublisher events) {
        this(repository, events, new Random());
    }

    /** Test seam - lets tests force/deny a shock deterministically. */
    RateShockService(RateShockRepository repository, DomainEventPublisher events, Random random) {
        this.repository = repository;
        this.events = events;
        this.random = random;
    }

    @Transactional
    public void maybeTriggerShock(LocalDate date) {
        if (random.nextDouble() >= DAILY_PROBABILITY) {
            return;
        }
        BigDecimal delta = random.nextBoolean() ? SHOCK_MAGNITUDE : SHOCK_MAGNITUDE.negate();
        repository.insert(date, delta);
        BigDecimal cumulativeOffset = repository.sumDeltaOnOrBefore(date);
        log.info("Rate shock triggered on {}: delta {}, cumulative offset {}", date, delta, cumulativeOffset);
        events.publish(new RateShockTriggeredEvent(date, delta, cumulativeOffset));
    }

    /** Effective rate offset as of {@code asOfDate} - the sum of every shock dated on or before it. */
    public BigDecimal offsetAsOf(LocalDate asOfDate) {
        return repository.sumDeltaOnOrBefore(asOfDate);
    }

    public List<RateShock> history() {
        return repository.findAll();
    }
}
