package io.github.ivarm1984.banksim.eventinjector;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.ivarm1984.banksim.clock.ClockService;

@RestController
@RequestMapping("/api/event-injector")
public class EventInjectorController {

    private final RateShockService rateShockService;
    private final RecessionShockService recessionShockService;
    private final ClockService clockService;

    public EventInjectorController(RateShockService rateShockService, RecessionShockService recessionShockService, ClockService clockService) {
        this.rateShockService = rateShockService;
        this.recessionShockService = recessionShockService;
        this.clockService = clockService;
    }

    @GetMapping("/status")
    public EventInjectorStatus status() {
        LocalDate today = clockService.state().simulatedTime().toLocalDate();
        return new EventInjectorStatus(rateShockService.offsetAsOf(today), recessionShockService.isActive(today));
    }

    @GetMapping("/rate-shocks")
    public List<RateShock> rateShocks() {
        return rateShockService.history();
    }

    @GetMapping("/recession-events")
    public List<RecessionEvent> recessionEvents() {
        return recessionShockService.history();
    }

    public record EventInjectorStatus(BigDecimal cumulativeRateOffset, boolean recessionActive) {
    }
}
