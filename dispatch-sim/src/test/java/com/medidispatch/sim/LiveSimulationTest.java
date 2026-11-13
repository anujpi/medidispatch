package com.medidispatch.sim;

import com.medidispatch.sim.WorldSnapshot.UnitState;
import com.medidispatch.sim.WorldSnapshot.UnitView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs the live simulation for real, on its threads, at full speed (300 simulated seconds per real second), for a
 * few real seconds each. Slower than the other tests, but it is the only way to check the threads work together.
 */
public class LiveSimulationTest {

    private static final Duration WAIT = Duration.ofSeconds(8);

    // a small, busy city: about one illness a minute, so things happen within seconds at full speed
    private static LiveConfig busyCity(double failureChance, Duration repairTime) {
        return new LiveConfig(7, 20, 60, 6, 8, 6, 3, 60, failureChance, failureChance, failureChance,
                repairTime, LiveSimulation.MAX_SPEED);
    }

    private LiveSimulation live;

    @AfterEach
    void stopThreads() {
        if (live != null) {
            live.stop();
        }
    }

    @Test
    void patientsFallIllUnitsAreSentAndNothingGoesWrongWhenNoUnitMisbehaves() {
        live = new LiveSimulation(busyCity(0, Duration.ofMinutes(20)));
        live.start();

        waitUntil(() -> live.latestSnapshot().report().totalCases() >= 5);
        waitUntil(() -> countServed(live.latestSnapshot().report()) >= 3);

        SimulationReport report = live.latestSnapshot().report();
        assertTrue(report.readings() > 0);
        assertEquals(0, report.timeouts(), "no unit misbehaves, so none should time out");
        assertEquals(0, report.failures(), "no unit misbehaves, so none should fail");
        assertEquals(0, live.engine().errors(), "no job on the engine thread should throw");
    }

    @Test
    void failedUnitsGoOfflineAndComeBackAfterRepair() {
        live = new LiveSimulation(busyCity(0.3, Duration.ofMinutes(3)));
        live.start();

        Set<String> wentOffline = new HashSet<>();
        Set<String> cameBack = new HashSet<>();
        waitUntil(() -> {
            for (UnitView u : live.latestSnapshot().units()) {
                if (u.state() == UnitState.OFFLINE) {
                    wentOffline.add(u.id());
                } else if (wentOffline.contains(u.id())) {
                    cameBack.add(u.id());
                }
            }
            return !cameBack.isEmpty();
        });

        SimulationReport report = live.latestSnapshot().report();
        assertTrue(report.timeouts() + report.failures() > 0);
        assertEquals(0, live.engine().errors(), "no job on the engine thread should throw");
    }

    @Test
    void pausingFreezesTheCityAndResumingLetsItRun() throws InterruptedException {
        live = new LiveSimulation(busyCity(0, Duration.ofMinutes(20)));
        live.start();
        waitUntil(() -> live.latestSnapshot().time().isAfter(LiveSimulation.START));

        live.pause();
        Thread.sleep(100);                                    // let the engine publish a paused snapshot
        Instant frozen = live.latestSnapshot().time();
        Thread.sleep(200);
        assertTrue(live.paused());
        assertEquals(frozen, live.latestSnapshot().time());

        live.resume();
        waitUntil(() -> live.latestSnapshot().time().isAfter(frozen));
    }

    @Test
    void stopEndsEveryThread() {
        live = new LiveSimulation(busyCity(0, Duration.ofMinutes(20)));
        live.start();
        waitUntil(() -> live.latestSnapshot().report().readings() > 0);

        live.stop();

        assertTrue(live.threadsStopped());
    }

    @Test
    void speedCanBeChangedWithinItsLimits() {
        live = new LiveSimulation(busyCity(0, Duration.ofMinutes(20)));
        live.setSpeed(10);
        assertEquals(10, live.speed());
        assertThrows(IllegalArgumentException.class, () -> live.setSpeed(0));
        assertThrows(IllegalArgumentException.class, () -> live.setSpeed(LiveSimulation.MAX_SPEED + 1));
        assertThrows(IllegalArgumentException.class, () -> new LiveSimulation(null));
    }

    @Test
    void beforeStartTheCityIsShownButTimeStandsStill() throws InterruptedException {
        live = new LiveSimulation(busyCity(0, Duration.ofMinutes(20)));
        Thread.sleep(50);

        WorldSnapshot first = live.latestSnapshot();
        assertTrue(live.paused(), "time does not run before start");
        assertTrue(Duration.between(LiveSimulation.START, first.time()).toSeconds() < 1);
        Thread.sleep(50);
        assertEquals(first.time(), live.engine().latestSnapshot().time());   // still the same moment
        assertEquals(60, first.patients().size());
        assertEquals(6 + 8 + 6 + 3, first.units().size());
        assertThrows(IllegalStateException.class, () -> {
            live.start();
            live.start();
        });
    }

    @Test
    void theConfigRejectsNonsense() {
        assertThrows(IllegalArgumentException.class, () -> new LiveConfig(1, 0, 10, 1, 1, 1, 1, 1, 0, 0, 0,
                Duration.ofMinutes(1), 60));
        assertThrows(IllegalArgumentException.class, () -> new LiveConfig(1, 20, -1, 1, 1, 1, 1, 1, 0, 0, 0,
                Duration.ofMinutes(1), 60));
        assertThrows(IllegalArgumentException.class, () -> new LiveConfig(1, 20, 10, 1, 1, 1, 1, 1, 0.5, 0.5, 0.5,
                Duration.ofMinutes(1), 60));
        assertThrows(IllegalArgumentException.class, () -> new LiveConfig(1, 20, 10, 1, 1, 1, 1, 1, 0, 0, 0,
                Duration.ZERO, 60));
        assertThrows(IllegalArgumentException.class, () -> new LiveConfig(1, 20, 10, 1, 1, 1, 1, 1, 0, 0, 0,
                Duration.ofMinutes(1), LiveSimulation.MAX_SPEED + 1));
        assertEquals(40, LiveConfig.bigCity().citySizeKm());
        assertEquals(25, LiveConfig.bigCity().meanHoursBetweenIllnesses(), 1e-9);   // 200 patients / 8 an hour
    }

    private static int countServed(SimulationReport report) {
        int served = 0;
        for (SimulationReport.BandStats band : report.byBand().values()) {
            served += band.served();
        }
        return served;
    }

    private static void waitUntil(BooleanSupplier condition) {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("condition not reached within " + WAIT.toSeconds() + " real seconds");
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted while waiting");
            }
        }
    }
}
