package io.github.ivarm1984.banksim.centralbank;

import org.springframework.stereotype.Service;

import io.github.ivarm1984.banksim.clock.ClockService;

/** Exposes the central bank's current rate corridor as of the simulated clock's current date. */
@Service
public class CentralBankService {

    private final ClockService clockService;
    private final CentralBankRateSchedule schedule;

    public CentralBankService(ClockService clockService, CentralBankRateSchedule schedule) {
        this.clockService = clockService;
        this.schedule = schedule;
    }

    public CentralBankRates currentRates() {
        return schedule.ratesOn(clockService.state().simulatedTime().toLocalDate());
    }
}
