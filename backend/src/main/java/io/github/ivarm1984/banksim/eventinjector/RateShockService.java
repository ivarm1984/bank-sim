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

import io.github.ivarm1984.banksim.centralbank.CentralBankRateSchedule;
import io.github.ivarm1984.banksim.event.DomainEventPublisher;

/**
 * Random central-bank rate shocks - a persisted, append-only log of signed
 * deltas (see {@link RateShockRepository}), so the deterministic
 * {@code CentralBankRateSchedule} base rate stays a pure function of date;
 * the shock is layered on top in {@code CentralBankService.currentRates()}.
 *
 * <p>Each shock's persisted delta is clamped so that base + offset lands
 * inside the policy-rate bounds on the shock date. Without that, a run of
 * hikes piled up an offset far above {@code MAX_POLICY_RATE} - the effective
 * rate sat pinned at the cap and later cuts were invisible for years. A shock
 * that would move nothing (a hike at the cap, a cut at the floor) isn't
 * recorded at all.
 */
@Service
public class RateShockService {

    private static final Logger log = LoggerFactory.getLogger(RateShockService.class);

    /** ~once every ~333 sim-days on average. */
    static final double DAILY_PROBABILITY = 0.003;
    /** 75bps - a "surprise" move, 3x the routine 25bps review step CentralBankRateSchedule uses. */
    static final BigDecimal SHOCK_MAGNITUDE = new BigDecimal("0.0075");
    /**
     * Direction bias: in the euro area recessions usually bring ECB cuts,
     * while outside one the surprise moves lean towards hikes (inflation
     * pressure in an expansion) - so margin compression tends to coincide
     * with the credit losses a recession brings, as it does in reality.
     */
    static final double HIKE_PROBABILITY_IN_RECESSION = 0.2;
    static final double HIKE_PROBABILITY_OUTSIDE_RECESSION = 0.6;

    private final RateShockRepository repository;
    private final CentralBankRateSchedule schedule;
    private final DomainEventPublisher events;
    private final Random random;

    @Autowired
    public RateShockService(RateShockRepository repository, CentralBankRateSchedule schedule, DomainEventPublisher events) {
        this(repository, schedule, events, new Random());
    }

    /** Test seam - lets tests force/deny a shock deterministically. */
    RateShockService(RateShockRepository repository, CentralBankRateSchedule schedule, DomainEventPublisher events, Random random) {
        this.repository = repository;
        this.schedule = schedule;
        this.events = events;
        this.random = random;
    }

    @Transactional
    public void maybeTriggerShock(LocalDate date, boolean recessionActive) {
        if (random.nextDouble() >= DAILY_PROBABILITY) {
            return;
        }
        double hikeProbability = recessionActive ? HIKE_PROBABILITY_IN_RECESSION : HIKE_PROBABILITY_OUTSIDE_RECESSION;
        BigDecimal requested = random.nextDouble() < hikeProbability ? SHOCK_MAGNITUDE : SHOCK_MAGNITUDE.negate();
        BigDecimal base = schedule.ratesOn(date).policyRate();
        BigDecimal offsetBefore = repository.sumDeltaOnOrBefore(date);
        BigDecimal effectiveBefore = CentralBankRateSchedule.clamp(base.add(offsetBefore));
        BigDecimal effectiveAfter = CentralBankRateSchedule.clamp(effectiveBefore.add(requested));
        if (effectiveAfter.compareTo(effectiveBefore) == 0) {
            return;
        }
        // Re-anchors the offset so base + offset == effectiveAfter exactly - also
        // pulling back any excess the base schedule's own drift left beyond a bound.
        BigDecimal delta = effectiveAfter.subtract(base).subtract(offsetBefore);
        repository.insert(date, delta);
        BigDecimal cumulativeOffset = offsetBefore.add(delta);
        log.info("Rate shock triggered on {}: delta {}, cumulative offset {}", date, delta, cumulativeOffset);
        events.publish(new RateShockTriggeredEvent(date, delta, cumulativeOffset));
    }

    /** Effective rate offset as of {@code asOfDate} - the sum of every shock dated on or before it. */
    public BigDecimal offsetAsOf(LocalDate asOfDate) {
        return repository.offsetAsOf(asOfDate);
    }

    public List<RateShock> history() {
        return repository.findAll();
    }
}
