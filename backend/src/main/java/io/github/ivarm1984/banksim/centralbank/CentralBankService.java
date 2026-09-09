package io.github.ivarm1984.banksim.centralbank;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.springframework.stereotype.Service;

import io.github.ivarm1984.banksim.clock.ClockService;
import io.github.ivarm1984.banksim.eventinjector.RateShockService;

/**
 * Exposes the central bank's current rate corridor as of the simulated
 * clock's current date: {@code CentralBankRateSchedule}'s deterministic
 * baseline, plus any persisted rate-shock offset (see
 * {@link RateShockService}) as of that same date.
 */
@Service
public class CentralBankService {

    private final ClockService clockService;
    private final CentralBankRateSchedule schedule;
    private final RateShockService rateShockService;

    public CentralBankService(ClockService clockService, CentralBankRateSchedule schedule, RateShockService rateShockService) {
        this.clockService = clockService;
        this.schedule = schedule;
        this.rateShockService = rateShockService;
    }

    public CentralBankRates currentRates() {
        LocalDate date = clockService.state().simulatedTime().toLocalDate();
        CentralBankRates base = schedule.ratesOn(date);
        BigDecimal policyRate = CentralBankRateSchedule.clamp(base.policyRate().add(rateShockService.offsetAsOf(date)));
        return new CentralBankRates(
                date,
                policyRate,
                policyRate.subtract(CentralBankRateSchedule.CORRIDOR_WIDTH),
                policyRate.add(CentralBankRateSchedule.CORRIDOR_WIDTH));
    }
}
