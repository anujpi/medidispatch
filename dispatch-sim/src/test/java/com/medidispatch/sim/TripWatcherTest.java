package com.medidispatch.sim;

import com.medidispatch.core.domain.AssignmentRole;
import com.medidispatch.core.domain.Location;
import com.medidispatch.sim.TripWatcher.Change;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The crews' view of the live feed: pictures in, trip changes out. No server involved. */
public class TripWatcherTest {

    private final ServiceClient json = new ServiceClient(URI.create("http://localhost"));
    private final TripWatcher watcher = new TripWatcher();

    // one live picture with the given trips (id, unit, case, role) and one open case per trip's case
    private JsonNode picture(String... trips) {
        StringBuilder tripJson = new StringBuilder();
        StringBuilder caseJson = new StringBuilder();
        for (int i = 0; i < trips.length; i++) {
            String[] t = trips[i].split(",");
            if (i > 0) {
                tripJson.append(',');
                caseJson.append(',');
            }
            tripJson.append("""
                    {"id": "%s", "unitId": "%s", "caseId": "%s", "role": "%s", "status": "ASSIGNED"}
                    """.formatted(t[0], t[1], t[2], t[3]));
            caseJson.append("""
                    {"id": "%s", "patientId": "p-%s", "latitude": 12.97, "longitude": 77.59}
                    """.formatted(t[2], t[2]));
        }
        return json.parse("{\"activeTrips\": [" + tripJson + "], \"openCases\": [" + caseJson + "]}");
    }

    @Test
    void aNewTripTellsTheUnitWhereToGo() {
        List<Change> changes = watcher.update(picture("t1,als-1,c1,PRIMARY"));

        assertEquals(1, changes.size());
        Change c = changes.get(0);
        assertEquals(Change.Kind.NEW, c.kind());
        assertEquals("t1", c.tripId());
        assertEquals("als-1", c.unitId());
        assertEquals("p-c1", c.patientId());
        assertEquals(AssignmentRole.PRIMARY, c.role());
        assertEquals(new Location(12.97, 77.59), c.target());
    }

    @Test
    void theSamePictureAgainChangesNothing() {
        watcher.update(picture("t1,als-1,c1,PRIMARY"));

        assertTrue(watcher.update(picture("t1,als-1,c1,PRIMARY")).isEmpty());
    }

    /** A first responder demoted when a better unit is needed: the role changes on the same trip. */
    @Test
    void aRoleChangeIsReported() {
        watcher.update(picture("t1,bls-1,c1,PRIMARY"));

        List<Change> changes = watcher.update(picture("t1,bls-1,c1,SUPPORT"));

        assertEquals(1, changes.size());
        assertEquals(Change.Kind.ROLE, changes.get(0).kind());
        assertEquals(AssignmentRole.SUPPORT, changes.get(0).role());
    }

    /** A trip no longer in the active list has ended; how it ended is asked for separately. */
    @Test
    void aTripThatDisappearedIsGone() {
        watcher.update(picture("t1,als-1,c1,PRIMARY", "t2,als-2,c2,PRIMARY"));

        List<Change> changes = watcher.update(picture("t2,als-2,c2,PRIMARY"));

        assertEquals(1, changes.size());
        assertEquals(Change.Kind.GONE, changes.get(0).kind());
        assertEquals("t1", changes.get(0).tripId());
        assertEquals("als-1", changes.get(0).unitId());
    }

    /** New trips first, then role changes, then the ended ones (in id order). */
    @Test
    void changesComeInAFixedOrder() {
        watcher.update(picture("t1,bls-1,c1,PRIMARY", "t8,als-8,c8,PRIMARY", "t9,als-9,c9,PRIMARY"));

        List<Change> changes = watcher.update(picture("t1,bls-1,c1,SUPPORT", "t5,als-5,c5,PRIMARY"));

        assertEquals(List.of(Change.Kind.NEW, Change.Kind.ROLE, Change.Kind.GONE, Change.Kind.GONE),
                List.of(changes.get(0).kind(), changes.get(1).kind(), changes.get(2).kind(), changes.get(3).kind()));
        assertEquals(List.of("t5", "t1", "t8", "t9"), List.of(changes.get(0).tripId(), changes.get(1).tripId(),
                changes.get(2).tripId(), changes.get(3).tripId()));
    }

    @Test
    void anEmptyCityHasNoChanges() {
        assertTrue(watcher.update(JsonMapper.builder().build().readTree("{}")).isEmpty());
    }
}
