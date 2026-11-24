package com.medidispatch.sim;

import com.medidispatch.core.domain.Location;
import com.medidispatch.core.domain.ResourceType;
import com.medidispatch.sim.SimulatedUnit.Behaviour;
import com.medidispatch.sim.SimulatedUnit.TickResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

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

    // ---- the second leg and the drive home (S2) ----

    private static final Location HOSPITAL = new Location(12.94, 77.50);  // about 2.2 km beyond the patient
    private static final Location BASE = new Location(12.88, 77.50);      // about 2.2 km behind the start

    /** After the patient, the same trip drives on to the hospital, reporting again, and arrives a second time. */
    @Test
    void goToDrivesTheSameTripOnToTheHospital() {
        SimulatedUnit unit = ambulanceOnTrip(Behaviour.NORMAL);
        answer(unit);
        assertEquals(TickResult.ARRIVED, driveFor(unit, 100));
        assertFalse(unit.reportsPosition());                          // on scene

        unit.goTo(HOSPITAL);

        assertEquals("trip-1", unit.assignmentId());
        assertTrue(unit.reportsPosition());
        assertEquals(TickResult.ARRIVED, driveFor(unit, 100));
        assertEquals(HOSPITAL, unit.position());
    }

    /** A planned breakdown is for the way to the patient only: the leg with the patient on board always goes well. */
    @Test
    void theLegToTheHospitalHasNoPlannedFailure() {
        SimulatedUnit unit = ambulanceOnTrip(Behaviour.NORMAL);
        answer(unit);
        driveFor(unit, 100);
        unit.goTo(HOSPITAL);

        assertEquals(TickResult.ARRIVED, driveFor(unit, 100));
        assertFalse(unit.broken());
        assertFalse(unit.silent());
    }

    @Test
    void goToWithoutATripIsRefused() {
        SimulatedUnit unit = new SimulatedUnit("als-1", ResourceType.AMBULANCE_ALS, START);

        assertThrows(IllegalStateException.class, () -> unit.goTo(HOSPITAL));
    }

    /** A free unit with a home drives back, reporting on the way, and goes quiet once home. */
    @Test
    void aFreeUnitWithAHomeDrivesBackAndStopsReportingThere() {
        SimulatedUnit unit = new SimulatedUnit("als-1", ResourceType.AMBULANCE_ALS, START, BASE);

        unit.headHome();
        assertTrue(unit.goingHome());
        assertTrue(unit.reportsPosition());
        for (int i = 0; i < 100 && unit.goingHome(); i++) {
            unit.tick(TICK);
        }

        assertEquals(BASE, unit.position());
        assertFalse(unit.goingHome());
        assertFalse(unit.reportsPosition());
    }

    @Test
    void aNewTripReplacesTheDriveHome() {
        SimulatedUnit unit = new SimulatedUnit("als-1", ResourceType.AMBULANCE_ALS, START, BASE);
        unit.headHome();

        unit.assign("trip-2", PATIENT, Behaviour.NORMAL);

        assertFalse(unit.goingHome());
        assertEquals(PATIENT, unit.target());
    }

    /** The in-memory simulator's units have no home: they park where the trip ended, as before. */
    @Test
    void aUnitWithoutAHomeStaysWhereItIs() {
        SimulatedUnit unit = new SimulatedUnit("als-1", ResourceType.AMBULANCE_ALS, START);

        unit.headHome();
        unit.tick(TICK);

        assertFalse(unit.goingHome());
        assertEquals(START, unit.position());
    }

    // ---------- along a route (R5b) ----------

    // a detour round a "lake": east, north, then west to the patient (about 6.5 km instead of 2.2)
    private static final Location EAST = new Location(12.90, 77.52);
    private static final Location NORTH_EAST = new Location(12.92, 77.52);
    private static final List<Location> DETOUR = List.of(START, EAST, NORTH_EAST, PATIENT);
    private static final double ROUTE_SECONDS = 600;

    @Test
    void aUnitFollowsTheRouteAndArrivesInTheRoutesTime() {
        SimulatedUnit unit = ambulanceOnTrip(Behaviour.NORMAL);
        unit.follow(DETOUR, ROUTE_SECONDS);
        answer(unit);

        double furthestEast = 0;
        int ticks = 0;
        TickResult last = TickResult.NOTHING;
        while (last != TickResult.ARRIVED && ticks < 100) {
            last = unit.tick(TICK);
            ticks++;
            furthestEast = Math.max(furthestEast, unit.position().longitude());
            assertTrue(unit.position().longitude() >= 77.50 - 1e-9, "never west of the route");
        }

        assertEquals(TickResult.ARRIVED, last);
        assertEquals(PATIENT, unit.position());
        assertEquals(77.52, furthestEast, 1e-9);                                  // it went round the corner
        assertTrue(ticks >= 59 && ticks <= 61, "600 s at 10 s a tick, took " + ticks);
    }

    /** Halfway is half the ROAD: on the detour that is the middle of the north leg, not the middle of the line. */
    @Test
    void aBreakdownHappensHalfwayAlongTheRoute() {
        SimulatedUnit unit = ambulanceOnTrip(Behaviour.BREAKS_DOWN);
        unit.follow(DETOUR, ROUTE_SECONDS);
        answer(unit);

        driveFor(unit, 100);

        assertTrue(unit.broken());
        assertEquals(77.52, unit.position().longitude(), 1e-6);                   // on the north leg
        assertEquals(12.91, unit.position().latitude(), 0.002);                    // about its middle
    }

    /** The route's last point may be rounded: the unit still ends exactly at the patient. */
    @Test
    void theUnitEndsExactlyAtTheDestination() {
        SimulatedUnit unit = ambulanceOnTrip(Behaviour.NORMAL);
        unit.follow(List.of(START, new Location(12.9200001, 77.5000001)), 60);
        answer(unit);

        assertEquals(TickResult.ARRIVED, driveFor(unit, 100));
        assertEquals(PATIENT, unit.position());
    }

    @Test
    void theDriveHomeCanFollowARouteToo() {
        SimulatedUnit unit = new SimulatedUnit("als-1", ResourceType.AMBULANCE_ALS, PATIENT, START);
        unit.headHome();
        unit.follow(List.of(PATIENT, NORTH_EAST, EAST, START), ROUTE_SECONDS);

        int ticks = 0;
        while (unit.goingHome() && ticks < 100) {
            unit.tick(TICK);
            ticks++;
        }

        assertFalse(unit.goingHome());
        assertEquals(START, unit.position());
        assertTrue(ticks >= 59 && ticks <= 61, "took " + ticks);
    }

    @Test
    void aRouteNeedsSomewhereToGoAndTwoPoints() {
        SimulatedUnit free = new SimulatedUnit("als-1", ResourceType.AMBULANCE_ALS, START);
        assertThrows(IllegalStateException.class, () -> free.follow(DETOUR, ROUTE_SECONDS));

        SimulatedUnit busy = ambulanceOnTrip(Behaviour.NORMAL);
        assertThrows(IllegalArgumentException.class, () -> busy.follow(List.of(PATIENT), ROUTE_SECONDS));
        assertThrows(IllegalArgumentException.class, () -> busy.follow(null, ROUTE_SECONDS));
    }

    /** No time given: the type's own speed is kept. */
    @Test
    void aRouteWithoutATimeKeepsTheTypesSpeed() {
        SimulatedUnit unit = ambulanceOnTrip(Behaviour.NORMAL);
        double typeSpeed = unit.legSpeedKmPerHour();

        unit.follow(DETOUR, 0);

        assertEquals(typeSpeed, unit.legSpeedKmPerHour());
    }
}
