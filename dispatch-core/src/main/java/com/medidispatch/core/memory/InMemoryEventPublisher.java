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

    /**
     * Lists only the events published from a given position on, so a long-running reader does not copy
     * everything again each time. Keep the last {@link #size()} as a bookmark and pass it next time.
     *
     * @param from the position of the first event wanted, 0 for the start
     * @return the events from that position, in publishing order, as a copy
     * @throws IllegalArgumentException if {@code from} is negative or past the end
     */
    public List<DomainEvent> publishedSince(int from) {
        if (from < 0 || from > events.size()) {
            throw new IllegalArgumentException("from must be between 0 and " + events.size() + ", got " + from);
        }
        return List.copyOf(events.subList(from, events.size()));
    }

    /**
     * Counts the events published so far.
     *
     * @return how many events have been published
     */
    public int size() {
        return events.size();
    }
    /** Forgets every recorded event; tests call it after setup so they only see the events they care about. */
    public void clear(){
        events.clear();
    }
}
