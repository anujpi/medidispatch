package com.medidispatch.core.event;

import java.time.Instant;

public interface DomainEvent {
    Instant occurredAt();
}
