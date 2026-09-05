package io.github.ivarm1984.banksim.agents;

import java.time.LocalDateTime;

/** A programmed customer behavior that reacts to the simulation clock. */
public interface Agent {

    void onTick(LocalDateTime simulatedNow, AgentContext context);
}
