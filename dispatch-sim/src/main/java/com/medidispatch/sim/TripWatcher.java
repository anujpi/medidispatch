package com.medidispatch.sim;

import com.medidispatch.core.domain.AssignmentRole;
import com.medidispatch.core.domain.Location;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Works out what changed for the crews between two pictures of the city from the live feed: which trips are new,
 * which changed role, and which are gone. This is how the simulated crews hear about their trips, much as a crew's
 * app would. Not thread-safe: one reader thread uses it.
 */
final class TripWatcher {

    /**
     * One thing a crew needs to hear about.
     *
     * @param kind      a new trip, a role change, or a trip that has ended (gone from the active list)
     * @param tripId    the trip
     * @param unitId    the unit on it
     * @param caseId    the case it serves
     * @param patientId the case's patient ({@code null} for GONE)
     * @param role      the trip's role ({@code null} for GONE)
     * @param target    where to drive: the patient (NEW only, else {@code null})
     */
    record Change(Kind kind, String tripId, String unitId, String caseId, String patientId, AssignmentRole role,
                  Location target) {

        /** What kind of change it is. */
        enum Kind { NEW, ROLE, GONE }
    }

    private record Seen(String unitId, String caseId, AssignmentRole role) {}

    private Map<String, Seen> known = new HashMap<>();

    /**
     * Compares a new picture with the previous one. New trips come first, in the order the picture lists them,
     * then role changes, then the trips that are gone (sorted by id, so the order never depends on a hash map).
     *
     * @param snapshot one live-feed picture: it has {@code activeTrips} and {@code openCases}
     * @return what changed; empty if nothing did
     */
    List<Change> update(JsonNode snapshot) {
        Map<String, JsonNode> casesById = new HashMap<>();
        for (JsonNode c : snapshot.path("openCases")) {
            casesById.put(ServiceClient.text(c, "id"), c);
        }
        List<Change> news = new ArrayList<>();
        List<Change> roles = new ArrayList<>();
        Map<String, Seen> now = new HashMap<>();
        for (JsonNode trip : snapshot.path("activeTrips")) {
            String tripId = ServiceClient.text(trip, "id");
            Seen seen = new Seen(ServiceClient.text(trip, "unitId"), ServiceClient.text(trip, "caseId"),
                    AssignmentRole.valueOf(ServiceClient.text(trip, "role")));
            now.put(tripId, seen);
            Seen before = known.get(tripId);
            JsonNode c = casesById.get(seen.caseId());
            String patientId = c == null ? null : ServiceClient.text(c, "patientId");
            if (before == null) {
                Location target = c == null ? null
                        : new Location(c.path("latitude").asDouble(), c.path("longitude").asDouble());
                news.add(new Change(Change.Kind.NEW, tripId, seen.unitId(), seen.caseId(), patientId, seen.role(),
                        target));
            } else if (before.role() != seen.role()) {
                roles.add(new Change(Change.Kind.ROLE, tripId, seen.unitId(), seen.caseId(), patientId, seen.role(),
                        null));
            }
        }
        List<String> goneIds = new ArrayList<>();
        for (String tripId : known.keySet()) {
            if (!now.containsKey(tripId)) {
                goneIds.add(tripId);
            }
        }
        goneIds.sort((a, b) -> a.compareTo(b));
        List<Change> changes = new ArrayList<>(news);
        changes.addAll(roles);
        for (String tripId : goneIds) {
            Seen was = known.get(tripId);
            changes.add(new Change(Change.Kind.GONE, tripId, was.unitId(), was.caseId(), null, null, null));
        }
        known = now;
        return changes;
    }
}
