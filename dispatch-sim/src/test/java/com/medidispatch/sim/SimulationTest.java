package com.medidispatch.sim;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs the whole engine end to end through the simulator. These are slower than unit tests (each run plays two
 * simulated hours), but still take well under a second each.
 */
public class SimulationTest {

    private static final SimulationConfig NO_FAILURES = SimulationConfig.defaults().withFailures(0, 0, 0);

    @Test
    void withNoFailuresEveryCaseGetsItsUnit() {
        SimulationReport report = new Simulation(NO_FAILURES).run();

        assertTrue(report.totalCases() > 0, "the run should have some patients falling ill");
        assertEquals(0, report.timeouts());
        assertEquals(0, report.failures());
        assertEquals(0, report.reassignedCases());
        for (SimulationReport.BandStats band : report.byBand().values()) {
            assertEquals(band.cases(), band.served());                  // every unit sent also arrived
        }
        // a case can still be on scene when the clock stops, so open cases at the end are not checked here
    }

    @Test
    void everyPatientReportsEveryMinuteAndNothingIsIgnored() {
        SimulationReport report = new Simulation(NO_FAILURES).run();

        int minutes = (int) NO_FAILURES.duration().toMinutes();
        assertEquals(NO_FAILURES.patientCount() * minutes, report.readings());
        assertEquals(0, report.ignoredReadings());
        assertEquals(Duration.ofHours(2), report.simulated());
    }

    @Test
    void theEngineCopesWithUnitsThatFailAndStillReplacesThem() {
        SimulationReport report = new Simulation(SimulationConfig.defaults().withFailures(0.2, 0.2, 0.2)).run();

        assertTrue(report.timeouts() > 0, "some units should never answer");
        assertTrue(report.failures() > 0, "some units should break down or lose signal");
        assertTrue(report.reassignedCases() > 0, "lost units should be replaced");
    }

    @Test
    void withNoNursesOrDoctorsNoFirstRespondersAreSent() {
        SimulationConfig d = NO_FAILURES;
        SimulationConfig noResponders = new SimulationConfig(d.seed(), d.duration(), d.tick(), d.patientCount(),
                d.deteriorationChance(), d.alsCount(), d.blsCount(), 0, 0, 0, 0, 0);

        assertEquals(0, new Simulation(noResponders).run().firstRespondersSent());
    }

    @Test
    void theSameSeedBuildsTheSameCity() {
        SimulationReport first = new Simulation(SimulationConfig.defaults()).run();
        SimulationReport second = new Simulation(SimulationConfig.defaults()).run();

        // ids inside the engine are random, so only the shape of the run is compared (see BACKLOG.md)
        assertEquals(first.readings(), second.readings());
        assertEquals(first.totalCases(), second.totalCases());
    }

    @Test
    void stepMovesTimeOnByOneTickAndTheReportWorksMidRun() {
        Simulation simulation = new Simulation(NO_FAILURES);
        Instant before = simulation.now();

        simulation.step();

        assertEquals(before.plus(NO_FAILURES.tick()), simulation.now());
        assertFalse(simulation.finished());
        assertEquals(Duration.ofSeconds(10), simulation.report().simulated());
    }

    @Test
    void aFinishedRunCannotBeSteppedAgain() {
        Simulation simulation = new Simulation(NO_FAILURES);
        simulation.run();

        assertTrue(simulation.finished());
        assertThrows(IllegalStateException.class, () -> simulation.step());
    }

    @Test
    void aMissingConfigIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Simulation(null));
    }
}
