package com.medidispatch.core.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

public class ResourceTest {

    private static final Location BANGALORE = new Location(12.9716, 77.5946);
    private static final Location CHENNAI = new Location(13.0827, 80.2707);
    private static final Instant T0 = Instant.parse("2026-10-07T12:00:00Z");
    private static final Location HERE = new Location(0, 0);
    private static final Location JITTER = new Location(0.0001, 0);    // 11 m away: under the 20 m threshold
    private static final Location STEP = new Location(0.0002, 0);      // 22 m away: over the threshold
    private static final Location FAR_AWAY = new Location(0.001, 0);   // 111 m away

    private Resource newResource() {
        return new Resource("r1", ResourceType.AMBULANCE_ALS, BANGALORE);
    }
    private Resource unitAtHere() {
        return new Resource("r1", ResourceType.AMBULANCE_ALS, HERE);
    }
    @Test
    void aNewResourceHasNeverBeenSeenOrMoved() {
        Resource r = unitAtHere();

        assertTrue(r.lastSeenAt().isEmpty());
        assertTrue(r.lastMovedAt().isEmpty());
    }

    @Test
    void aBigMoveUpdatesLocationLastSeenAndLastMoved() {
        Resource r = unitAtHere();

        r.reportLocation(FAR_AWAY, T0);

        assertEquals(FAR_AWAY, r.location());
        assertEquals(T0, r.lastSeenAt().get());
        assertEquals(T0, r.lastMovedAt().get());
    }

    @Test
    void gpsJitterCountsAsSeenButNotAsMoved() {
        Resource r = unitAtHere();

        r.reportLocation(JITTER, T0);

        assertEquals(T0, r.lastSeenAt().get());
        assertTrue(r.lastMovedAt().isEmpty());
        assertEquals(JITTER, r.location());          // the position is still recorded
    }

    @Test
    void smallStepsAddUpAgainstTheLastCountedPosition() {
        Resource r = unitAtHere();

        r.reportLocation(JITTER, T0);                 // 11 m from the anchor: not movement
        r.reportLocation(STEP, T0.plusSeconds(10));   // 22 m from the anchor: movement

        assertEquals(T0.plusSeconds(10), r.lastMovedAt().get());
    }

    @Test
    void lastMovedAtDoesNotChangeWhileTheUnitStaysPut() {
        Resource r = unitAtHere();
        r.reportLocation(FAR_AWAY, T0);

        r.reportLocation(FAR_AWAY, T0.plusSeconds(30));

        assertEquals(T0.plusSeconds(30), r.lastSeenAt().get());
        assertEquals(T0, r.lastMovedAt().get());      // still the time of the real move
    }

    @Test
    void updateLocationResetsTheMovementAnchor() {
        Resource r = unitAtHere();
        r.updateLocation(FAR_AWAY);

        // 11 m from the NEW position. Measured from the old anchor (HERE) this would be 122 m.
        r.reportLocation(new Location(0.0011, 0), T0);

        assertTrue(r.lastMovedAt().isEmpty());
    }

    @Test
    void reportLocationRejectsNullsAndChangesNothing() {
        Resource r = unitAtHere();

        assertThrows(IllegalArgumentException.class, () -> r.reportLocation(null, T0));
        assertThrows(IllegalArgumentException.class, () -> r.reportLocation(FAR_AWAY, null));

        assertEquals(HERE, r.location());
        assertTrue(r.lastSeenAt().isEmpty());
        assertTrue(r.lastMovedAt().isEmpty());
    }
    // ---------- construction ----------

    @Test
    void newResourceIsAvailableWithTheGivenValues() {
        Resource r = newResource();

        assertEquals("r1", r.id());
        assertEquals(ResourceType.AMBULANCE_ALS, r.type());
        assertEquals(BANGALORE, r.location());
        assertEquals(ResourceStatus.AVAILABLE, r.status());
        assertTrue(r.isAvailable());
    }

    @Test
    void nullOrBlankIdIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new Resource(null, ResourceType.NURSE, BANGALORE));
        assertThrows(IllegalArgumentException.class,
                () -> new Resource("  ", ResourceType.NURSE, BANGALORE));
    }

    @Test
    void nullTypeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Resource("r1", null, BANGALORE));
    }

    @Test
    void nullLocationIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new Resource("r1", ResourceType.NURSE, null));
    }

    // ---------- transitions ----------

    @Test
    void assigningAnAvailableResourceMakesItUnavailable() {
        Resource r = newResource();

        r.transitionTo(ResourceStatus.ASSIGNED);

        assertEquals(ResourceStatus.ASSIGNED, r.status());
        assertFalse(r.isAvailable());
    }

    @Test
    void assigningAnAlreadyAssignedResourceThrowsAndLeavesItAssigned() {
        Resource r = newResource();
        r.transitionTo(ResourceStatus.ASSIGNED);

        assertThrows(IllegalStateException.class, () -> r.transitionTo(ResourceStatus.ASSIGNED));

        assertEquals(ResourceStatus.ASSIGNED, r.status());
    }

    @Test
    void releasingAnAssignedResourceMakesItAvailableAgain() {
        Resource r = newResource();
        r.transitionTo(ResourceStatus.ASSIGNED);

        r.transitionTo(ResourceStatus.AVAILABLE);

        assertTrue(r.isAvailable());
    }

    @Test
    void offlineResourceCannotBeAssignedDirectly() {
        Resource r = newResource();
        r.transitionTo(ResourceStatus.OFFLINE);

        assertThrows(IllegalStateException.class, () -> r.transitionTo(ResourceStatus.ASSIGNED));

        assertEquals(ResourceStatus.OFFLINE, r.status());
        assertFalse(r.isAvailable());
    }

    @Test
    void offlineResourceComesBackThroughAvailable() {
        Resource r = newResource();
        r.transitionTo(ResourceStatus.OFFLINE);

        r.transitionTo(ResourceStatus.AVAILABLE);
        r.transitionTo(ResourceStatus.ASSIGNED);

        assertEquals(ResourceStatus.ASSIGNED, r.status());
    }

    @Test
    void nullNextStatusIsRejectedAndStatusUnchanged() {
        Resource r = newResource();

        assertThrows(IllegalArgumentException.class, () -> r.transitionTo(null));

        assertEquals(ResourceStatus.AVAILABLE, r.status());
    }

    @Test
    void errorMessageNamesBothStatuses() {
        Resource r = newResource();
        r.transitionTo(ResourceStatus.OFFLINE);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> r.transitionTo(ResourceStatus.ASSIGNED));

        assertTrue(e.getMessage().contains("OFFLINE"));
        assertTrue(e.getMessage().contains("ASSIGNED"));
    }

    // ---------- location ----------

    @Test
    void locationCanBeUpdatedWhileAvailable() {
        Resource r = newResource();

        r.updateLocation(CHENNAI);

        assertEquals(CHENNAI, r.location());
    }

    @Test
    void locationCanBeUpdatedWhileAssigned() {         // units move while on a job
        Resource r = newResource();
        r.transitionTo(ResourceStatus.ASSIGNED);

        r.updateLocation(CHENNAI);

        assertEquals(CHENNAI, r.location());
    }

    @Test
    void nullLocationUpdateIsRejectedAndLocationUnchanged() {
        Resource r = newResource();

        assertThrows(IllegalArgumentException.class, () -> r.updateLocation(null));

        assertEquals(BANGALORE, r.location());
    }

}