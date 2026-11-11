package com.medidispatch.sim;

import com.medidispatch.core.domain.AssignmentRole;
import com.medidispatch.core.domain.Location;
import com.medidispatch.core.domain.ResourceType;
import com.medidispatch.core.domain.RiskBand;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * A read-only picture of the simulated city at one moment, for drawing it.
 * <p>
 * It holds only plain values (ids, locations, enums), never the simulation's own objects, so whoever draws it
 * cannot change the simulation, and everything in it belongs to the same moment.
 *
 * @param time         the simulated time of the picture
 * @param bounds       the edges of the city
 * @param patients     every patient, in a fixed order
 * @param units        every unit, in a fixed order
 * @param waitingCases cases waiting in the queue for a unit
 * @param recentEvents the latest things the engine did, newest first, as readable lines
 * @param report       the statistics so far
 * @param finished     whether the run has reached its end
 */
public record WorldSnapshot(Instant time,
                            CityBounds bounds,
                            List<PatientView> patients,
                            List<UnitView> units,
                            int waitingCases,
                            List<String> recentEvents,
                            SimulationReport report,
                            boolean finished) {

    /**
     * The edges of the city in decimal degrees.
     *
     * @param minLatitude  southern edge
     * @param minLongitude western edge
     * @param maxLatitude  northern edge
     * @param maxLongitude eastern edge
     */
    public record CityBounds(double minLatitude, double minLongitude, double maxLatitude, double maxLongitude) {}

    /**
     * One patient.
     *
     * @param id   the patient id
     * @param home where the patient is
     * @param band the band of the patient's open case, or empty if they have none (healthy)
     */
    public record PatientView(String id, Location home, Optional<RiskBand> band) {}

    /**
     * One unit.
     *
     * @param id       the unit id
     * @param type     what kind of unit it is
     * @param position where it is now
     * @param state    what it is doing
     * @param target   where it is going, or empty when free
     * @param role     its role on the current trip, or empty when free
     */
    public record UnitView(String id, ResourceType type, Location position, UnitState state,
                           Optional<Location> target, Optional<AssignmentRole> role) {}

    /** What a unit is doing. */
    public enum UnitState {
        /** Free to be sent. */
        FREE,
        /** Has a trip but the crew has not answered yet. */
        ANSWERING,
        /** On its way to the patient. */
        DRIVING,
        /** With the patient. */
        ON_SCENE,
        /** Stopped halfway with a breakdown (still reporting its position). */
        BROKEN_DOWN,
        /** Still moving, but its signal is lost. */
        NO_SIGNAL,
        /** Taken out of service by the engine after a timeout or failure. */
        OFFLINE
    }

    /**
     * Validates the snapshot and makes its lists unmodifiable copies.
     *
     * @throws IllegalArgumentException if any field is missing or {@code waitingCases} is negative
     */
    public WorldSnapshot {
        if (time == null || bounds == null || patients == null || units == null || recentEvents == null
                || report == null) {
            throw new IllegalArgumentException("time, bounds, patients, units, recentEvents and report are required");
        }
        if (waitingCases < 0) {
            throw new IllegalArgumentException("waitingCases cannot be negative, got " + waitingCases);
        }
        patients = List.copyOf(patients);
        units = List.copyOf(units);
        recentEvents = List.copyOf(recentEvents);
    }
}
