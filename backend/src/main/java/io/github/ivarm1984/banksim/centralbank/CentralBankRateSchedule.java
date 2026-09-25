package io.github.ivarm1984.banksim.centralbank;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Random;

import org.springframework.stereotype.Component;

import io.github.ivarm1984.banksim.clock.SimulationClock;

/**
 * Deterministic policy-rate walk, standing in for the central bank's own
 * rate-setting committee.
 *
 * <p>The policy rate is reviewed every {@link #REVIEW_PERIOD_DAYS} simulated
 * days (an ECB Governing Council meets roughly every six weeks — that's the
 * period used here), starting from {@link SimulationClock#epoch()}. At each
 * review it holds, cuts, or hikes by one {@link #STEP} (25bps), clamped to
 * {@code [MIN_POLICY_RATE, MAX_POLICY_RATE]}. The deposit facility and
 * marginal lending rates sit in a fixed corridor around the policy rate.
 *
 * <p>The walk is a pure function of the simulated date: each call replays a
 * {@link Random} seeded with {@link #SEED} from review 1 up to the target
 * review, so replaying the same dates (including after a clock
 * {@code reset}) always reproduces the same rates. (Re-seeding a fresh
 * {@code Random} per review index — e.g. {@code SEED + reviewIndex} — looks
 * tempting but is a real trap: {@code java.util.Random}'s first draw from
 * consecutive seeds is strongly correlated, which was caught here by a test
 * asserting the rate actually moves over a long horizon, not just that it
 * stays in bounds. Advancing one shared, sequentially-seeded generator
 * avoids that.) This schedule stays a pure function of date - it does not
 * react to simulated events. Random rate shocks (see
 * {@code eventinjector.RateShockService}) are layered on top of this
 * baseline drift in {@code CentralBankService.currentRates()}, not here.
 */
@Component
public class CentralBankRateSchedule {

    static final BigDecimal INITIAL_POLICY_RATE = new BigDecimal("0.0300");
    static final BigDecimal STEP = new BigDecimal("0.0025");
    static final BigDecimal CORRIDOR_WIDTH = new BigDecimal("0.0025");
    public static final BigDecimal MIN_POLICY_RATE = BigDecimal.ZERO;
    public static final BigDecimal MAX_POLICY_RATE = new BigDecimal("0.0750");
    static final int REVIEW_PERIOD_DAYS = 42;
    static final long SEED = 20260101L;

    private static final double CUT_THRESHOLD = 0.20;
    private static final double HOLD_THRESHOLD = 0.80;

    public CentralBankRates ratesOn(LocalDate simulatedDate) {
        BigDecimal policyRate = policyRateOn(simulatedDate);
        return new CentralBankRates(
                simulatedDate,
                policyRate,
                policyRate.subtract(CORRIDOR_WIDTH),
                policyRate.add(CORRIDOR_WIDTH));
    }

    BigDecimal policyRateOn(LocalDate simulatedDate) {
        int reviewIndex = reviewIndexFor(simulatedDate);
        Random random = new Random(SEED);
        BigDecimal rate = INITIAL_POLICY_RATE;
        for (int review = 1; review <= reviewIndex; review++) {
            rate = clamp(rate.add(STEP.multiply(BigDecimal.valueOf(decision(random)))));
        }
        return rate;
    }

    private static int reviewIndexFor(LocalDate simulatedDate) {
        long daysSinceEpoch = ChronoUnit.DAYS.between(SimulationClock.epoch(), simulatedDate);
        return Math.max(0, (int) (daysSinceEpoch / REVIEW_PERIOD_DAYS));
    }

    private static int decision(Random random) {
        double roll = random.nextDouble();
        if (roll < CUT_THRESHOLD) {
            return -1;
        }
        if (roll < HOLD_THRESHOLD) {
            return 0;
        }
        return 1;
    }

    /** Public so CentralBankService/RateShockService can clamp a rate-shock-adjusted policy rate to the same bounds. */
    public static BigDecimal clamp(BigDecimal rate) {
        if (rate.compareTo(MIN_POLICY_RATE) < 0) {
            return MIN_POLICY_RATE;
        }
        if (rate.compareTo(MAX_POLICY_RATE) > 0) {
            return MAX_POLICY_RATE;
        }
        return rate;
    }
}
