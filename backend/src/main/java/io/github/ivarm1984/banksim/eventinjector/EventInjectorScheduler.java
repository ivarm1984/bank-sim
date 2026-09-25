package io.github.ivarm1984.banksim.eventinjector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import io.github.ivarm1984.banksim.clock.DayRolledOverEvent;
import io.github.ivarm1984.banksim.loan.LoanStagingService;
import io.github.ivarm1984.banksim.loan.LoanWriteOffService;

/**
 * Drives EventInjector's daily steps, in order: recession tick first (its
 * state biases the rate shock's direction and feeds the forward-looking
 * PDs), then the rate shock roll, then - if the recession just started or
 * ended - a book-wide ECL remeasurement under the new macro scenario, and
 * the daily days-past-due staging pass, and finally the write-off pass for
 * loans that have sat in default too long. Each step is independently
 * try/caught so one failing step never blocks the others.
 */
@Service
public class EventInjectorScheduler {

    private static final Logger log = LoggerFactory.getLogger(EventInjectorScheduler.class);

    private final RateShockService rateShockService;
    private final RecessionShockService recessionShockService;
    private final LoanStagingService loanStagingService;
    private final LoanWriteOffService loanWriteOffService;

    public EventInjectorScheduler(
            RateShockService rateShockService, RecessionShockService recessionShockService,
            LoanStagingService loanStagingService, LoanWriteOffService loanWriteOffService) {
        this.rateShockService = rateShockService;
        this.recessionShockService = recessionShockService;
        this.loanStagingService = loanStagingService;
        this.loanWriteOffService = loanWriteOffService;
    }

    @EventListener
    @Order(DayRolledOverEvent.ORDER_EVENT_INJECTOR)
    public void onDayRolledOver(DayRolledOverEvent event) {
        boolean wasActive = recessionShockService.isActive(event.newDate().minusDays(1));
        boolean recessionActive = recessionShockService.isActive(event.newDate());
        try {
            recessionActive = recessionShockService.tick(event.newDate());
        } catch (Exception e) {
            log.warn("Recession shock tick failed for {}", event.newDate(), e);
        }

        try {
            rateShockService.maybeTriggerShock(event.newDate(), recessionActive);
        } catch (Exception e) {
            log.warn("Rate shock roll failed for {}", event.newDate(), e);
        }

        if (recessionActive != wasActive) {
            try {
                loanStagingService.remeasureAll(event.newDate(), recessionActive);
            } catch (Exception e) {
                log.warn("Book-wide ECL remeasurement failed for {}", event.newDate(), e);
            }
        }

        try {
            loanStagingService.evaluateDaily(event.newDate(), recessionActive);
        } catch (Exception e) {
            log.warn("Loan staging pass failed for {}", event.newDate(), e);
        }

        try {
            loanWriteOffService.writeOffDaily(event.newDate());
        } catch (Exception e) {
            log.warn("Loan write-off pass failed for {}", event.newDate(), e);
        }
    }
}
