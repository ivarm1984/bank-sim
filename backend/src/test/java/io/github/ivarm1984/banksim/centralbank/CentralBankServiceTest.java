package io.github.ivarm1984.banksim.centralbank;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.ivarm1984.banksim.clock.ClockService;
import io.github.ivarm1984.banksim.clock.SimulationClock;

class CentralBankServiceTest {

    private final SimulationClock clock = new SimulationClock();
    private final ClockService clockService = new ClockService(clock, event -> { });
    private final CentralBankRateSchedule schedule = new CentralBankRateSchedule();
    private final CentralBankService centralBankService = new CentralBankService(clockService, schedule);

    @Test
    void currentRatesReflectTheSimulatedClockDate() {
        CentralBankRates atEpoch = centralBankService.currentRates();

        assertThat(atEpoch).isEqualTo(schedule.ratesOn(SimulationClock.epoch()));

        clock.setSpeed(200 * 24 * 60);
        clock.advance();

        CentralBankRates afterAdvancing = centralBankService.currentRates();

        assertThat(afterAdvancing).isEqualTo(schedule.ratesOn(clockService.state().simulatedTime().toLocalDate()));
    }
}
