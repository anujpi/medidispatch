package com.medidispatch.core.port;

import com.medidispatch.core.event.DomainEvent;

/**
 * Port: announces what happened in the engine (a unit was assigned, a case changed status, and so on).
 * <p>
 * The engine publishes only <em>after</em> the change has been saved, and never publishes a change that
 * did not happen. Implementations <strong>must not throw</strong>: a failed announcement must never undo
 * or block a dispatch. Kafka (through an outbox table) will implement it in the service layer.
 */
public interface EventPublisher {

    /**
     * Announces one event.
     *
     * @param event what happened
     */
    void publish(DomainEvent event);
}
