package com.medidispatch.sim;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The console commands, without a console or a service: building an {@link HttpSimulation} sends nothing (that
 * starts with setUp), and a city that has not been started is paused.
 */
class HttpSimulatorMainTest {

    private final HttpSimulation city = new HttpSimulation(LiveConfig.bangalore(), URI.create("http://localhost:1"));

    @Test
    void aNumberSetsTheSpeed() {
        String reply = HttpSimulatorMain.command(" 30 ", city);

        assertEquals(30, city.speed());
        assertTrue(reply.startsWith("speed 30"), reply);
    }

    @Test
    void pauseAndResume() {
        HttpSimulatorMain.command("r", city);
        assertFalse(city.paused());

        String reply = HttpSimulatorMain.command("P", city);

        assertTrue(city.paused());
        assertTrue(reply.contains("time out"), "pausing warns about the service's real-time checks");
        HttpSimulatorMain.command("r", city);
        assertFalse(city.paused());
    }

    @Test
    void theStatusShowsSpeedOrPaused() {
        assertTrue(HttpSimulatorMain.command("s", city).contains("PAUSED"));

        HttpSimulatorMain.command("r", city);
        HttpSimulatorMain.command("45", city);

        assertTrue(HttpSimulatorMain.command("s", city).contains("x45"));
    }

    @Test
    void qQuits() {
        assertEquals(HttpSimulatorMain.QUIT, HttpSimulatorMain.command("q", city));
    }

    @Test
    void anythingElseChangesNothing() {
        int before = city.speed();

        assertEquals(HttpSimulatorMain.HELP, HttpSimulatorMain.command("faster", city));
        assertEquals(HttpSimulatorMain.HELP, HttpSimulatorMain.command("", city));
        assertTrue(HttpSimulatorMain.command("0", city).startsWith("speed must be"));
        assertTrue(HttpSimulatorMain.command("301", city).startsWith("speed must be"));
        assertEquals(before, city.speed());
        assertThrows(IllegalArgumentException.class, () -> city.setSpeed(0));
    }
}
