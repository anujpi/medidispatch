package com.medidispatch.sim.fx;

import com.medidispatch.sim.Simulation;
import com.medidispatch.sim.SimulationConfig;
import com.medidispatch.sim.WorldSnapshot;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

/**
 * The simulator window: the map on the left, the side panel on the right, and an animation loop that plays the
 * simulation at the chosen speed.
 * <p>
 * Everything runs on the JavaFX thread: the {@link AnimationTimer} steps the simulation and redraws, and the
 * buttons only change whether it plays and how fast. No other threads, so nothing needs locking.
 * Start it with {@link SimulatorLauncher}.
 */
public final class SimulatorApp extends Application {

    private static final int START_SPEED = 60;

    private Simulation simulation;
    private TickPacer pacer;
    private MapView map;
    private SidePanel panel;
    private boolean playing = true;
    private long lastFrameNanos = -1;     // -1 until the first frame

    @Override
    public void start(Stage stage) {
        SimulationConfig config = SimulationConfig.defaults();
        simulation = new Simulation(config);
        pacer = new TickPacer(config.tick(), START_SPEED);
        map = new MapView();
        panel = new SidePanel(START_SPEED);

        panel.setOnPlayPause(() -> {
            playing = !playing;
            panel.showPlaying(playing);
            pacer.reset();                                        // no burst of saved-up time after a pause
        });
        panel.setOnStep(() -> {
            if (!simulation.finished()) {
                simulation.step();
                redraw();
            }
        });
        panel.setOnSpeed(speed -> pacer.setSpeed(speed));

        BorderPane root = new BorderPane();
        root.setCenter(map);
        root.setRight(panel);
        Scene scene = new Scene(root);
        scene.getStylesheets().add(getClass().getResource("simulator.css").toExternalForm());

        stage.setTitle("MediDispatch simulator (seed " + config.seed() + ")");
        stage.setScene(scene);
        stage.setResizable(false);
        stage.show();
        redraw();

        new AnimationTimer() {
            @Override
            public void handle(long now) {
                onFrame(now);
            }
        }.start();
    }

    // called about 60 times a second, on the JavaFX thread
    private void onFrame(long nowNanos) {
        long elapsed = lastFrameNanos < 0 ? 0 : nowNanos - lastFrameNanos;
        lastFrameNanos = nowNanos;
        if (!playing || simulation.finished()) {
            return;
        }
        int ticks = pacer.ticksFor(elapsed);
        for (int i = 0; i < ticks && !simulation.finished(); i++) {
            simulation.step();
        }
        if (ticks > 0) {
            redraw();                                             // nothing changed, nothing to repaint
        }
    }

    private void redraw() {
        WorldSnapshot snapshot = simulation.snapshot();
        map.draw(snapshot);
        panel.update(snapshot);
    }
}
