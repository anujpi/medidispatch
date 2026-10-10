package com.medidispatch.core.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ResourceTest {

    private static final Location BANGALORE = new Location(12.9716, 77.5946);
    private static final Location CHENNAI = new Location(13.0827, 80.2707);

    private Resource newResource() {
        return new Resource("r1", ResourceType.AMBULANCE_ALS, BANGALORE);
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