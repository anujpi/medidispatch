package com.medidispatch.core.port;

import com.medidispatch.core.event.DomainEvent;

public interface EventPublisher {
    void publish(DomainEvent event);
}
