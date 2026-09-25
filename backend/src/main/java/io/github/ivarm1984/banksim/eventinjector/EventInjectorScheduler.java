package io.github.ivarm1984.banksim.eventinjector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import io.github.ivarm1984.banksim.clock.DayRolledOverEvent;
import io.github.ivarm1984.banksim.loan.LoanPhaseTransitionService;

/**
 * Drives EventInjector's daily dice rolls, in order: rate shock, then
 * recession tick, then the BUSINESS loan phase-transition roll (which needs
 * that day's fresh recession-active state). Each step is independently
 * try/caught so one failing roll never blocks the others.
 */
@Service
public class EventInjectorScheduler {

    private static final Logger log = LoggerFactory.getLogger(EventInjectorScheduler.class);

    private final RateShockService rateShockService;
    private final RecessionShockService recessionShockService;
    private final LoanPhaseTransitionService loanPhaseTransitionService;

    public EventInjectorScheduler(
            RateShockService rateShockService, RecessionShockService recessionShockService,
            LoanPhaseTransitionService loanPhaseTransitionService) {
        this.rateShockService = rateShockService;
        this.recessionShockService = recessionShockService;
        this.loanPhaseTransitionService = loanPhaseTransitionService;
    }

    @EventListener
    @Order(DayRolledOverEvent.ORDER_EVENT_INJECTOR)
    public void onDayRolledOver(DayRolledOverEvent event) {
        try {
            rateShockService.maybeTriggerShock(event.newDate());
        } catch (Exception e) {
            log.warn("Rate shock roll failed for {}", event.newDate(), e);
        }

        boolean recessionActive = recessionShockService.isActive(event.newDate());
        try {
            recessionActive = recessionShockService.tick(event.newDate());
        } catch (Exception e) {
            log.warn("Recession shock tick failed for {}", event.newDate(), e);
        }

        try {
            loanPhaseTransitionService.rollDailyTransitions(event.newDate(), recessionActive);
        } catch (Exception e) {
            log.warn("Loan phase transition roll failed for {}", event.newDate(), e);
        }
    }
}
