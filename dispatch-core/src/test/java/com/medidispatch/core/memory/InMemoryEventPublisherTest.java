package com.medidispatch.core.memory;

import com.medidispatch.core.domain.AssignmentRole;
import com.medidispatch.core.event.DomainEvent;
import com.medidispatch.core.event.UnitAssigned;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class InMemoryEventPublisherTest {

    private static final Instant T = Instant.parse("2026-10-07T12:00:00Z");

    private final InMemoryEventPublisher publisher = new InMemoryEventPublisher();

    private DomainEvent event(String assignmentId) {
        return new UnitAssigned(assignmentId, "case-1", "r1", AssignmentRole.PRIMARY, T);
    }

    @Test
    void startsEmpty() {
        assertTrue(publisher.published().isEmpty());
    }

    @Test
    void keepsEventsInTheOrderTheyWerePublished() {
        DomainEvent first = event("a1");
        DomainEvent second = event("a2");

        publisher.publish(first);
        publisher.publish(second);

        assertEquals(List.of(first, second), publisher.published());
    }

    @Test
    void theReturnedListCannotChangeThePublishersHistory() {
        publisher.publish(event("a1"));

        assertThrows(UnsupportedOperationException.class, () -> publisher.published().clear());
        assertEquals(1, publisher.published().size());
    }

    @Test
    void aListTakenEarlierDoesNotChangeWhenMoreEventsArePublished() {
        publisher.publish(event("a1"));
        List<DomainEvent> snapshot = publisher.published();

        publisher.publish(event("a2"));

        assertEquals(1, snapshot.size());
        assertEquals(2, publisher.published().size());
    }

    @Test
    void clearEmptiesTheHistory() {
        publisher.publish(event("a1"));

        publisher.clear();

        assertTrue(publisher.published().isEmpty());
    }

    @Test
    void aNullEventIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> publisher.publish(null));
    }
}