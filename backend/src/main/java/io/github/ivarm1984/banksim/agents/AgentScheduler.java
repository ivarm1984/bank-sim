package io.github.ivarm1984.banksim.agents;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import io.github.ivarm1984.banksim.account.AccountService;
import io.github.ivarm1984.banksim.clock.ClockTickedEvent;
import io.github.ivarm1984.banksim.event.DomainEventPublisher;
import io.github.ivarm1984.banksim.transaction.TransactionService;

/**
 * Drives every registered {@link Agent} on each simulated clock tick. Each
 * agent's {@code onTick} is isolated in its own try/catch so one failing
 * agent doesn't stop the others or the tick itself.
 */
@Service
public class AgentScheduler {

    private static final Logger log = LoggerFactory.getLogger(AgentScheduler.class);

    private final List<Agent> agents = new CopyOnWriteArrayList<>();
    private final AgentContext context;

    public AgentScheduler(TransactionService transactionService, AccountService accountService, DomainEventPublisher events) {
        this.context = new AgentContext(transactionService, accountService, events);
    }

    public void register(Agent agent) {
        agents.add(agent);
    }

    @EventListener
    public void onClockTicked(ClockTickedEvent event) {
        for (Agent agent : agents) {
            try {
                agent.onTick(event.simulatedNow(), context);
            } catch (Exception e) {
                log.warn("Agent {} failed on tick {}", agent, event.simulatedNow(), e);
            }
        }
    }
}
