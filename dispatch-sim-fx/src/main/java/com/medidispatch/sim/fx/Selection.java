package com.medidispatch.sim.fx;

import com.medidispatch.core.domain.AssignmentRole;
import com.medidispatch.core.domain.Location;
import com.medidispatch.core.domain.ResourceType;
import com.medidispatch.sim.WorldSnapshot;
import com.medidispatch.sim.WorldSnapshot.PatientView;
import com.medidispatch.sim.WorldSnapshot.UnitView;

/**
 * Describes the unit or patient clicked on the map, in one readable line for the side panel, for example
 * {@code "als-2 · ALS ambulance · driving · main response to p17 · 3.2 km to go"}.
 * <p>
 * Pure logic with no JavaFX in it, so it is easy to test.
 */
final class Selection {

    private Selection() {}

    /**
     * Describes a unit or patient as it is in a snapshot.
     *
     * @param snapshot the city at one moment
     * @param id       a unit or patient id
     * @return the description, or an empty string if nothing in the snapshot has that id
     */
    static String describe(WorldSnapshot snapshot, String id) {
        for (UnitView unit : snapshot.units()) {
            if (unit.id().equals(id)) {
                return describeUnit(snapshot, unit);
            }
        }
        for (PatientView patient : snapshot.patients()) {
            if (patient.id().equals(id)) {
                return patient.band().isPresent()
                        ? patient.id() + " · " + patient.band().get() + " · help needed"
                        : patient.id() + " · healthy";
            }
        }
        return "";
    }

    private static String describeUnit(WorldSnapshot snapshot, UnitView unit) {
        String text = unit.id() + " · " + kindOf(unit.type()) + " · " + readable(unit.state().name());
        if (unit.target().isEmpty() || unit.role().isEmpty()) {
            return text;
        }
        String role = unit.role().get() == AssignmentRole.PRIMARY ? "main response" : "first responder";
        Location target = unit.target().get();
        double km = unit.position().distanceKmTo(target);
        return text + " · " + role + " to " + patientAt(snapshot, target) + String.format(" · %.1f km to go", km);
    }

    // the patient who lives where the unit is going
    private static String patientAt(WorldSnapshot snapshot, Location place) {
        for (PatientView patient : snapshot.patients()) {
            if (patient.home().equals(place)) {
                return patient.id();
            }
        }
        return "a patient";
    }

    private static String kindOf(ResourceType type) {
        return switch (type) {
            case AMBULANCE_ALS -> "ALS ambulance";
            case AMBULANCE_BLS -> "BLS ambulance";
            case NURSE -> "nurse";
            case DOCTOR -> "doctor";
            case COURIER -> "courier";
        };
    }

    // ON_SCENE -> "on scene"
    private static String readable(String constant) {
        return constant.toLowerCase().replace('_', ' ');
    }
}
