package com.medidispatch.core.memory;

import com.medidispatch.core.event.DomainEvent;
import com.medidispatch.core.port.EventPublisher;

import java.util.ArrayList;
import java.util.List;

public class InMemoryEventPublisher implements EventPublisher {
    private final List<DomainEvent> events = new ArrayList<>();


    @Override
    public void publish(DomainEvent event) {
        if(event == null){
            throw new IllegalArgumentException("event is required");
        }
        events.add(event);
    }
    public List<DomainEvent> published(){
        return List.copyOf(events);
    }
    public void clear(){
        events.clear();
    }
}
