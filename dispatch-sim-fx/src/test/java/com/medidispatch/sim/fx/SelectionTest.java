package com.medidispatch.sim.fx;

import com.medidispatch.core.domain.AssignmentRole;
import com.medidispatch.core.domain.Location;
import com.medidispatch.core.domain.ResourceType;
import com.medidispatch.core.domain.RiskBand;
import com.medidispatch.sim.SimulationReport;
import com.medidispatch.sim.WorldSnapshot;
import com.medidispatch.sim.WorldSnapshot.PatientView;
import com.medidispatch.sim.WorldSnapshot.UnitState;
import com.medidispatch.sim.WorldSnapshot.UnitView;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class SelectionTest {

    private static final Location HOME = new Location(12.90, 77.50);
    private static final Location THREE_KM_NORTH = new Location(12.927, 77.50);
    private static final SimulationReport EMPTY_REPORT =
            new SimulationReport(Duration.ZERO, 0, 0, Map.of(), 0, 0, 0, 0, 0, 0, 0, 0, 0);

    private WorldSnapshot snapshotWith(List<PatientView> patients, List<UnitView> units) {
        return new WorldSnapshot(Instant.parse("2026-01-05T08:00:00Z"),
                new WorldSnapshot.CityBounds(12.8, 77.4, 13.0, 77.6), patients, units, 0, List.of(), EMPTY_REPORT, false);
    }

    @Test
    void aDrivingAmbulanceShowsWhereItIsGoingAndHowFar() {
        WorldSnapshot snapshot = snapshotWith(
                List.of(new PatientView("p17", HOME, Optional.of(RiskBand.CRITICAL))),
                List.of(new UnitView("als-2", ResourceType.AMBULANCE_ALS, THREE_KM_NORTH, UnitState.DRIVING,
                        Optional.of(HOME), Optional.of(AssignmentRole.PRIMARY))));

        assertEquals("als-2 · ALS ambulance · driving · main response to p17 · 3.0 km to go",
                Selection.describe(snapshot, "als-2"));
    }

    @Test
    void aFirstResponderIsCalledThat() {
        WorldSnapshot snapshot = snapshotWith(
                List.of(new PatientView("p17", HOME, Optional.of(RiskBand.CRITICAL))),
                List.of(new UnitView("nurse-6", ResourceType.NURSE, HOME, UnitState.ON_SCENE,
                        Optional.of(HOME), Optional.of(AssignmentRole.SUPPORT))));

        assertTrue(Selection.describe(snapshot, "nurse-6").contains("on scene · first responder to p17"));
    }

    @Test
    void aFreeUnitHasNoTrip() {
        WorldSnapshot snapshot = snapshotWith(List.of(), List.of(new UnitView("bls-1", ResourceType.AMBULANCE_BLS,
                HOME, UnitState.FREE, Optional.empty(), Optional.empty())));

        assertEquals("bls-1 · BLS ambulance · free", Selection.describe(snapshot, "bls-1"));
    }

    @Test
    void patientsShowTheirBandOrThatTheyAreHealthy() {
        WorldSnapshot snapshot = snapshotWith(List.of(
                new PatientView("p01", HOME, Optional.empty()),
                new PatientView("p02", HOME, Optional.of(RiskBand.HIGH))), List.of());

        assertEquals("p01 · healthy", Selection.describe(snapshot, "p01"));
        assertEquals("p02 · HIGH · help needed", Selection.describe(snapshot, "p02"));
    }

    @Test
    void anUnknownIdDescribesNothing() {
        assertEquals("", Selection.describe(snapshotWith(List.of(), List.of()), "ghost"));
    }
}
