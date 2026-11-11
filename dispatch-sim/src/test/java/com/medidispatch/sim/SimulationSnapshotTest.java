package com.medidispatch.sim;

import com.medidispatch.sim.WorldSnapshot.UnitState;
import com.medidispatch.sim.WorldSnapshot.UnitView;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class SimulationSnapshotTest {

    private static final SimulationConfig NO_FAILURES = SimulationConfig.defaults().withFailures(0, 0, 0);

    // steps until some unit is busy, so there is something on the move to look at
    private WorldSnapshot firstSnapshotWithABusyUnit(Simulation simulation) {
        while (!simulation.finished()) {
            simulation.step();
            WorldSnapshot snapshot = simulation.snapshot();
            for (UnitView unit : snapshot.units()) {
                if (unit.state() != UnitState.FREE) {
                    return snapshot;
                }
            }
        }
        throw new AssertionError("no unit was ever sent");
    }

    @Test
    void atTheStartEveryoneIsThereAndEveryUnitIsFree() {
        SimulationConfig config = NO_FAILURES;
        WorldSnapshot snapshot = new Simulation(config).snapshot();

        assertEquals(config.patientCount(), snapshot.patients().size());
        assertEquals(config.alsCount() + config.blsCount() + config.nurseCount() + config.doctorCount(),
                snapshot.units().size());
        for (UnitView unit : snapshot.units()) {
            assertEquals(UnitState.FREE, unit.state());
            assertTrue(unit.target().isEmpty());
            assertTrue(unit.role().isEmpty());
        }
        for (WorldSnapshot.PatientView patient : snapshot.patients()) {
            assertTrue(patient.band().isEmpty());
        }
        assertEquals(0, snapshot.waitingCases());
        assertTrue(snapshot.recentEvents().isEmpty());
        assertFalse(snapshot.finished());
    }

    @Test
    void aUnitOnATripHasATargetARoleAndTheIllPatientShowsTheirBand() {
        WorldSnapshot snapshot = firstSnapshotWithABusyUnit(new Simulation(NO_FAILURES));

        UnitView busy = null;
        for (UnitView unit : snapshot.units()) {
            if (unit.state() != UnitState.FREE) {
                busy = unit;
            }
        }
        assertTrue(busy.target().isPresent());
        assertTrue(busy.role().isPresent());
        boolean someoneIsIll = false;
        for (WorldSnapshot.PatientView patient : snapshot.patients()) {
            if (patient.band().isPresent()) {
                someoneIsIll = true;
            }
        }
        assertTrue(someoneIsIll);
        assertFalse(snapshot.recentEvents().isEmpty());
    }

    @Test
    void everyPatientAndUnitStaysInsideTheCity() {
        Simulation simulation = new Simulation(NO_FAILURES);
        for (int i = 0; i < 200; i++) {
            simulation.step();
        }
        WorldSnapshot snapshot = simulation.snapshot();
        WorldSnapshot.CityBounds b = snapshot.bounds();

        for (UnitView unit : snapshot.units()) {
            assertTrue(unit.position().latitude() >= b.minLatitude() && unit.position().latitude() <= b.maxLatitude());
            assertTrue(unit.position().longitude() >= b.minLongitude() && unit.position().longitude() <= b.maxLongitude());
        }
    }

    @Test
    void theEventLogKeepsOnlyTheLatestLines() {
        Simulation simulation = new Simulation(NO_FAILURES);
        simulation.run();

        List<String> lines = simulation.snapshot().recentEvents();

        assertEquals(EventLog.SIZE, lines.size());
        for (String line : lines) {
            assertFalse(line.isBlank());
        }
    }

    @Test
    void unitsTakenOutOfServiceShowAsOffline() {
        Simulation simulation = new Simulation(SimulationConfig.defaults().withFailures(0.3, 0.3, 0.3));
        simulation.run();

        boolean someoneOffline = false;
        for (UnitView unit : simulation.snapshot().units()) {
            if (unit.state() == UnitState.OFFLINE) {
                someoneOffline = true;
            }
        }
        assertTrue(someoneOffline);
    }

    @Test
    void aFinishedRunSaysSoAndCarriesTheFullReport() {
        Simulation simulation = new Simulation(NO_FAILURES);
        SimulationReport report = simulation.run();

        WorldSnapshot snapshot = simulation.snapshot();

        assertTrue(snapshot.finished());
        assertEquals(report.totalCases(), snapshot.report().totalCases());
        assertEquals(simulation.now(), snapshot.time());
    }

    @Test
    void aSnapshotIsACopyThatCannotBeChanged() {
        Simulation simulation = new Simulation(NO_FAILURES);
        WorldSnapshot before = simulation.snapshot();

        simulation.step();

        assertNotEquals(before.time(), simulation.snapshot().time());   // the old picture did not move
        assertThrows(UnsupportedOperationException.class, () -> before.units().clear());
        assertThrows(UnsupportedOperationException.class, () -> before.recentEvents().add("x"));
    }

    @Test
    void aSnapshotWithMissingPartsIsRefused() {
        WorldSnapshot ok = new Simulation(NO_FAILURES).snapshot();

        assertThrows(IllegalArgumentException.class, () -> new WorldSnapshot(null, ok.bounds(), ok.patients(),
                ok.units(), 0, ok.recentEvents(), ok.report(), false));
        assertThrows(IllegalArgumentException.class, () -> new WorldSnapshot(ok.time(), ok.bounds(), ok.patients(),
                ok.units(), -1, ok.recentEvents(), ok.report(), false));
    }
}
