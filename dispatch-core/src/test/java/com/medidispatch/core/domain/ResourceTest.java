package com.medidispatch.core.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

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

    // ---- base: the hospital or clinic a unit belongs to ----

    @Test
    void aUnitCreatedWithoutABaseHasNone() {
        assertEquals(Optional.empty(), newResource().baseId());
    }

    @Test
    void theBaseIsKept() {
        Resource r = new Resource("r1", ResourceType.AMBULANCE_BLS, BANGALORE, "h-apollo");

        assertEquals(Optional.of("h-apollo"), r.baseId());
    }

    @Test
    void aBlankBaseIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new Resource("r1", ResourceType.AMBULANCE_BLS, BANGALORE, " "));
        assertThrows(IllegalArgumentException.class,
                () -> Resource.restore("u1", ResourceType.NURSE, "", null, ResourceStatus.AVAILABLE, HERE, HERE, null, null));
    }

    // ---- restore: rebuilding a stored unit (used by storage adapters) ----

    /** restore brings back every field, including the movement reference point and both times. */
    @Test
    void restoreRebuildsEveryField() {
        Resource r = Resource.restore("u1", ResourceType.NURSE, "clinic-7", null, ResourceStatus.OFFLINE, CHENNAI,
                BANGALORE, T0, T0.minusSeconds(60));

        assertEquals("u1", r.id());
        assertEquals(ResourceType.NURSE, r.type());
        assertEquals(Optional.of("clinic-7"), r.baseId());
        assertEquals(ResourceStatus.OFFLINE, r.status());
        assertEquals(CHENNAI, r.location());
        assertEquals(BANGALORE, r.movedFrom());
        assertEquals(Optional.of(T0), r.lastSeenAt());
        assertEquals(Optional.of(T0.minusSeconds(60)), r.lastMovedAt());
    }

    /** null times mean "never", exactly like a new unit. */
    @Test
    void restoreKeepsNeverAsEmpty() {
        Resource r = Resource.restore("u1", ResourceType.NURSE, null, null, ResourceStatus.AVAILABLE, HERE, HERE, null, null);

        assertTrue(r.lastSeenAt().isEmpty());
        assertTrue(r.lastMovedAt().isEmpty());
    }

    /**
     * Movement is measured from the restored reference point, not from the current position: STEP is only 11 m
     * from where the unit is (JITTER) but 22 m from where it last counted as moving (HERE), so it counts.
     */
    @Test
    void aRestoredUnitMeasuresMovementFromItsSavedReferencePoint() {
        Resource r = Resource.restore("u1", ResourceType.AMBULANCE_ALS, null, null, ResourceStatus.ASSIGNED, JITTER, HERE, T0, T0);

        r.reportLocation(STEP, T0.plusSeconds(10));

        assertEquals(Optional.of(T0.plusSeconds(10)), r.lastMovedAt());
        assertEquals(STEP, r.movedFrom());
    }

    /** A restored unit still obeys its rules: OFFLINE cannot go straight to ASSIGNED. */
    @Test
    void aRestoredUnitStillObeysTheRules() {
        Resource r = Resource.restore("u1", ResourceType.NURSE, null, null, ResourceStatus.OFFLINE, HERE, HERE, null, null);

        assertThrows(IllegalStateException.class, () -> r.transitionTo(ResourceStatus.ASSIGNED));
        assertEquals(ResourceStatus.OFFLINE, r.status());
    }

    /** restore checks its arguments like the constructor, plus status and movedFrom. */
    @Test
    void restoreRejectsMissingValues() {
        assertThrows(IllegalArgumentException.class,
                () -> Resource.restore("u1", ResourceType.NURSE, null, null, null, HERE, HERE, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> Resource.restore("u1", ResourceType.NURSE, null, null, ResourceStatus.AVAILABLE, HERE, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> Resource.restore(" ", ResourceType.NURSE, null, null, ResourceStatus.AVAILABLE, HERE, HERE, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> Resource.restore("u1", null, null, null, ResourceStatus.AVAILABLE, HERE, HERE, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> Resource.restore("u1", ResourceType.NURSE, null, null, ResourceStatus.AVAILABLE, null, HERE, null, null));
    }

    // ---- driver: who drives the unit ----

    @Test
    void aUnitWithoutADriverHasNone() {
        assertEquals(Optional.empty(), newResource().driver());
    }

    @Test
    void theDriverIsKeptByTheConstructorAndByRestore() {
        Contact ravi = new Contact("Ravi Kumar", "+91 98450 12345");

        Resource created = new Resource("r1", ResourceType.AMBULANCE_ALS, BANGALORE, "h-apollo", ravi);
        Resource restored = Resource.restore("r1", ResourceType.AMBULANCE_ALS, "h-apollo", ravi,
                ResourceStatus.ASSIGNED, BANGALORE, BANGALORE, null, null);

        assertEquals(Optional.of(ravi), created.driver());
        assertEquals(Optional.of(ravi), restored.driver());
        assertEquals(Optional.of("h-apollo"), restored.baseId());
    }
}
