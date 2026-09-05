package io.github.ivarm1984.banksim.event;

/**
 * Seam for publishing domain events, so the in-process mechanism backing it
 * (currently Spring's {@code ApplicationEventPublisher}) can later be
 * swapped for a real broker producer (Kafka/RabbitMQ/NATS) without touching
 * every publisher call site.
 */
public interface DomainEventPublisher {

    void publish(Object event);
}
