package com.medidispatch.core.event;

import java.time.Instant;

/**
 * Something that happened in the engine, announced through
 * {@link com.medidispatch.core.port.EventPublisher}.
 * <p>
 * Events are immutable records named in the past tense. The only thing all of them share is the time
 * they happened; each event type adds its own details.
 */
public interface DomainEvent {

    /**
     * Says when the event happened.
     *
     * @return when it happened, taken from the engine's clock
     */
    Instant occurredAt();
}
