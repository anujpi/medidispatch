package com.medidispatch.sim.fx;

import javafx.application.Application;

/**
 * Starts the simulator window. Run this class, not {@link SimulatorApp}: starting a JavaFX application through a
 * plain {@code main} avoids Java's "JavaFX runtime components are missing" check when JavaFX is on the classpath.
 */
public final class SimulatorLauncher {

    private SimulatorLauncher() {}

    /**
     * Opens the window.
     *
     * @param args passed on to JavaFX
     */
    public static void main(String[] args) {
        Application.launch(SimulatorApp.class, args);
    }
}
