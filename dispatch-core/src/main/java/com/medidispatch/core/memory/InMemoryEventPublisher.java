package com.medidispatch.core.memory;

import com.medidispatch.core.event.DomainEvent;
import com.medidispatch.core.port.EventPublisher;

import java.util.ArrayList;
import java.util.List;

/**
 * In-memory {@link EventPublisher} that simply records every event, so tests can check exactly what the
 * engine announced and in which order. Not thread-safe.
 */
public class InMemoryEventPublisher implements EventPublisher {
    private final List<DomainEvent> events = new ArrayList<>();


    @Override
    public void publish(DomainEvent event) {
        if(event == null){
            throw new IllegalArgumentException("event is required");
        }
        events.add(event);
    }
    /**
     * Lists everything published so far.
     *
     * @return the events in publishing order, as a copy that later publishing does not change
     */
    public List<DomainEvent> published(){
        return List.copyOf(events);
    }
    /** Forgets every recorded event; tests call it after setup so they only see the events they care about. */
    public void clear(){
        events.clear();
    }
}
