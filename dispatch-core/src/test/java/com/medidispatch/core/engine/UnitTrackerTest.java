package com.medidispatch.core.engine;

import com.medidispatch.core.domain.Location;
import com.medidispatch.core.domain.Resource;
import com.medidispatch.core.domain.ResourceType;
import com.medidispatch.core.memory.InMemoryResourceRepository;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

public class UnitTrackerTest {

    private static final Instant START = Instant.parse("2026-10-07T12:00:00Z");
    private static final Location ORIGIN = new Location(0, 0);
    private static final Location FAR_AWAY = new Location(0.001, 0);      // 111 m away

    private final MutableClock clock = new MutableClock(START);
    private final InMemoryResourceRepository resources = new InMemoryResourceRepository();
    private final UnitTracker tracker = new UnitTracker(resources, clock);

    private void addUnit(String id) {
        resources.save(new Resource(id, ResourceType.AMBULANCE_ALS, ORIGIN));
    }

    private Resource unit(String id) {
        return resources.findById(id).get();
    }

    @Test
    void aReportIsStampedWithTheClockTimeAndStored() {
        addUnit("als-1");
        clock.advance(Duration.ofSeconds(45));

        tracker.onLocationReported("als-1", FAR_AWAY);

        assertEquals(FAR_AWAY, unit("als-1").location());
        assertEquals(START.plusSeconds(45), unit("als-1").lastSeenAt().get());
        assertEquals(START.plusSeconds(45), unit("als-1").lastMovedAt().get());
    }

    @Test
    void aSecondReportAtTheSameSpotMovesLastSeenButNotLastMoved() {
        addUnit("als-1");
        tracker.onLocationReported("als-1", FAR_AWAY);
        clock.advance(Duration.ofSeconds(30));

        tracker.onLocationReported("als-1", FAR_AWAY);

        assertEquals(START.plusSeconds(30), unit("als-1").lastSeenAt().get());
        assertEquals(START, unit("als-1").lastMovedAt().get());
    }

    @Test
    void onlyTheReportedUnitIsTouched() {
        addUnit("als-1");
        addUnit("als-2");

        tracker.onLocationReported("als-1", FAR_AWAY);

        assertTrue(unit("als-2").lastSeenAt().isEmpty());
        assertEquals(ORIGIN, unit("als-2").location());
    }

    @Test
    void anUnknownUnitIsAnError() {
        assertThrows(IllegalStateException.class,
                () -> tracker.onLocationReported("ghost", FAR_AWAY));
    }

    @Test
    void badInputIsRejectedAndNothingChanges() {
        addUnit("als-1");

        assertThrows(IllegalArgumentException.class, () -> tracker.onLocationReported(null, FAR_AWAY));
        assertThrows(IllegalArgumentException.class, () -> tracker.onLocationReported("  ", FAR_AWAY));
        assertThrows(IllegalArgumentException.class, () -> tracker.onLocationReported("als-1", null));

        assertEquals(ORIGIN, unit("als-1").location());
        assertTrue(unit("als-1").lastSeenAt().isEmpty());
    }

    @Test
    void constructorRejectsMissingCollaborators() {
        assertThrows(IllegalArgumentException.class, () -> new UnitTracker(null, clock));
        assertThrows(IllegalArgumentException.class, () -> new UnitTracker(resources, null));
    }
}