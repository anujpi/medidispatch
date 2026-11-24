package com.medidispatch.sim;

import com.medidispatch.core.domain.Location;
import com.medidispatch.core.domain.VitalsReading;

import java.util.List;
import java.util.Optional;

/**
 * What the city's agents (patients' wearables and crews' apps) send to dispatch. Two implementations: {@link LiveEngine}
 * hands the messages to an engine in memory, {@link HttpLink} sends them to the running service over HTTP. The agents
 * cannot tell which one they talk to.
 * <p>
 * Every call may come from any agent thread and must return quickly: the implementation queues the work.
 */
interface CityLink {

    /**
     * A wearable reading.
     *
     * @param reading the reading
     */
    void reading(VitalsReading reading);

    /**
     * A crew answered its trip and set off.
     *
     * @param assignmentId the trip
     */
    void answered(String assignmentId);

    /**
     * A unit reports where it is.
     *
     * @param unitId the unit
     * @param where  its position
     */
    void position(String unitId, Location where);

    /**
     * A unit reached its patient.
     *
     * @param assignmentId the trip
     */
    void arrived(String assignmentId);

    /**
     * A repaired unit is ready again.
     *
     * @param unitId the unit
     */
    void backInService(String unitId);

    /**
     * The way by road, like a crew's navigation app gives it. Unlike the other calls this one WAITS for the answer
     * (a crew cannot set off without it), so call it only when a leg starts.
     *
     * @param from      where the unit is
     * @param to        where it is going
     * @param emergency {@code true} with the siren on (to a patient, to a hospital); {@code false} driving home
     * @return the way; empty to drive in a straight line (no roads known, or no way through)
     */
    default Optional<Way> route(Location from, Location to, boolean emergency) {
        return Optional.empty();                              // the in-memory city has no roads
    }

    /**
     * A route to drive.
     *
     * @param points  the way, start first, at least two points
     * @param seconds how long it takes in the traffic it was planned in
     */
    record Way(List<Location> points, double seconds) {

        /** Takes an unmodifiable copy of the points. */
        public Way {
            points = List.copyOf(points);
        }
    }
}
