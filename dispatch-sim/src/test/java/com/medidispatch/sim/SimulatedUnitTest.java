package com.medidispatch.sim;

import com.medidispatch.core.domain.Location;
import com.medidispatch.core.domain.ResourceType;
import com.medidispatch.sim.SimulatedUnit.Behaviour;
import com.medidispatch.sim.SimulatedUnit.TickResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

public class SimulatedUnitTest {

    private static final Duration TICK = Duration.ofSeconds(10);
    private static final Location START = new Location(12.90, 77.50);
    private static final Location PATIENT = new Location(12.92, 77.50);   // about 2.2 km away

    private SimulatedUnit ambulanceOnTrip(Behaviour behaviour) {
        SimulatedUnit unit = new SimulatedUnit("als-1", ResourceType.AMBULANCE_ALS, START);
        unit.assign("trip-1", PATIENT, behaviour);
        return unit;
    }

    // ticks until the crew answers (20 s), returning what the last tick said
    private TickResult answer(SimulatedUnit unit) {
        unit.tick(TICK);
        return unit.tick(TICK);
    }

    // ticks until arrival or until the limit, returning the last result
    private TickResult driveFor(SimulatedUnit unit, int ticks) {
        TickResult last = TickResult.NOTHING;
        for (int i = 0; i < ticks && last != TickResult.ARRIVED; i++) {
            last = unit.tick(TICK);
        }
        return last;
    }

    @Test
    void aFreeUnitDoesNothing() {
        SimulatedUnit unit = new SimulatedUnit("als-1", ResourceType.AMBULANCE_ALS, START);

        assertFalse(unit.busy());
        assertEquals(TickResult.NOTHING, unit.tick(TICK));
        assertFalse(unit.reportsPosition());
    }

    @Test
    void theCrewAnswersAfterTheDelayAndOnlyThenStartsReporting() {
        SimulatedUnit unit = ambulanceOnTrip(Behaviour.NORMAL);

        assertEquals(TickResult.NOTHING, unit.tick(TICK));            // 10 s: still answering
        assertFalse(unit.reportsPosition());
        assertEquals(TickResult.ACKNOWLEDGED, unit.tick(TICK));       // 20 s
        assertTrue(unit.reportsPosition());
    }

    @Test
    void aNormalUnitDrivesToThePatientAndArrives() {
        SimulatedUnit unit = ambulanceOnTrip(Behaviour.NORMAL);
        answer(unit);

        assertEquals(TickResult.ARRIVED, driveFor(unit, 100));         // 2.2 km at 50 km/h is about 16 ticks
        assertEquals(PATIENT, unit.position());
        assertFalse(unit.reportsPosition());                           // on scene, nothing to report
        assertEquals(TickResult.NOTHING, unit.tick(TICK));
    }

    @Test
    void aUnitThatNeverAnswersNeverMoves() {
        SimulatedUnit unit = ambulanceOnTrip(Behaviour.NEVER_ACKNOWLEDGES);

        assertEquals(TickResult.NOTHING, driveFor(unit, 100));
        assertEquals(START, unit.position());
        assertFalse(unit.reportsPosition());
    }

    @Test
    void aBrokenDownUnitStopsHalfwayButKeepsReporting() {
        SimulatedUnit unit = ambulanceOnTrip(Behaviour.BREAKS_DOWN);
        answer(unit);

        assertEquals(TickResult.NOTHING, driveFor(unit, 100));
        assertTrue(unit.broken());
        assertTrue(unit.reportsPosition());                            // the stall check needs these reports
        double left = unit.position().distanceKmTo(PATIENT);
        assertTrue(left > 0.5 && left < 1.2, "should be stuck about halfway, was " + left + " km away");
    }

    @Test
    void aUnitThatLosesSignalKeepsDrivingButGoesQuietAndCannotReportArriving() {
        SimulatedUnit unit = ambulanceOnTrip(Behaviour.LOSES_SIGNAL);
        answer(unit);

        assertEquals(TickResult.NOTHING, driveFor(unit, 100));         // arrives, but nobody hears it
        assertTrue(unit.silent());
        assertFalse(unit.reportsPosition());
        assertEquals(PATIENT, unit.position());
    }

    @Test
    void nursesAndDoctorsAreSlowerThanAmbulances() {
        SimulatedUnit ambulance = ambulanceOnTrip(Behaviour.NORMAL);
        SimulatedUnit nurse = new SimulatedUnit("nurse-1", ResourceType.NURSE, START);
        nurse.assign("trip-2", PATIENT, Behaviour.NORMAL);
        answer(ambulance);
        answer(nurse);

        ambulance.tick(TICK);
        nurse.tick(TICK);

        assertTrue(START.distanceKmTo(ambulance.position()) > START.distanceKmTo(nurse.position()));
    }

    @Test
    void freeingAUnitEndsTheTripWhereItIs() {
        SimulatedUnit unit = ambulanceOnTrip(Behaviour.NORMAL);
        answer(unit);
        driveFor(unit, 3);
        Location stoppedAt = unit.position();

        unit.free();

        assertFalse(unit.busy());
        assertNull(unit.assignmentId());
        assertEquals(stoppedAt, unit.position());
        assertEquals(TickResult.NOTHING, unit.tick(TICK));
    }

    @Test
    void aNewTripStartsFresh() {
        SimulatedUnit unit = ambulanceOnTrip(Behaviour.BREAKS_DOWN);
        answer(unit);
        driveFor(unit, 100);
        unit.free();

        unit.assign("trip-2", START, Behaviour.NORMAL);

        assertFalse(unit.broken());
        assertEquals("trip-2", unit.assignmentId());
        assertEquals(TickResult.NOTHING, unit.tick(TICK));             // has to answer again first
    }
}
