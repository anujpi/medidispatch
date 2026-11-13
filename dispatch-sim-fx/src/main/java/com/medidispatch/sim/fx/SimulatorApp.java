package com.medidispatch.sim.fx;

import com.medidispatch.sim.LiveConfig;
import com.medidispatch.sim.LiveSimulation;
import com.medidispatch.sim.WorldSnapshot;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

/**
 * The simulator window: the map on the left, the side panel on the right, showing a live, endless, random city.
 * <p>
 * The city runs on its own threads ({@link LiveSimulation}). This window never steps it: every frame, the
 * {@link AnimationTimer} (on the JavaFX thread) just picks up the latest snapshot the engine published and draws it
 * if it is new. The buttons only pause, resume or change the speed. Closing the window stops the threads.
 * Start it with {@link SimulatorLauncher}.
 */
public final class SimulatorApp extends Application {

    private LiveSimulation live;
    private MapView map;
    private SidePanel panel;
    private BorderPane root;
    private Theme theme = Theme.DARK;
    private WorldSnapshot latest;
    private String selectedId;

    @Override
    public void start(Stage stage) {
        LiveConfig config = LiveConfig.bigCity();
        live = new LiveSimulation(config);
        map = new MapView();
        panel = new SidePanel(config.startSpeed(), live.startTime());

        panel.setOnPlayPause(() -> {
            if (live.paused()) {
                live.resume();
            } else {
                live.pause();
            }
            panel.showPlaying(!live.paused());
        });
        panel.setOnSpeed(speed -> live.setSpeed(speed));
        panel.setOnToggleTheme(() -> applyTheme(theme.other()));
        panel.setOnResetView(() -> map.resetView());
        map.setOnSelect(id -> {
            selectedId = id;
            panel.showDetails(id == null ? "" : Selection.describe(latest, id));
        });

        ScrollPane side = new ScrollPane(panel);                  // scrolls if the window is short
        side.setFitToWidth(true);
        side.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        side.getStyleClass().add("side-scroll");
        root = new BorderPane();
        root.setCenter(map);
        root.setRight(side);
        Scene scene = new Scene(root, 1080, 780);
        scene.getStylesheets().add(getClass().getResource("simulator.css").toExternalForm());
        applyTheme(theme);

        stage.setTitle("MediDispatch live simulator (layout seed " + config.seed() + ")");
        stage.setScene(scene);
        stage.setMinWidth(820);
        stage.setMinHeight(560);
        stage.show();

        live.start();
        new AnimationTimer() {
            @Override
            public void handle(long now) {
                onFrame(now);
            }
        }.start();
    }

    /** Called by JavaFX when the window closes: stop the simulation's threads. */
    @Override
    public void stop() {
        if (live != null) {
            live.stop();
        }
    }

    // called about 60 times a second, on the JavaFX thread
    private void onFrame(long nowNanos) {
        WorldSnapshot newest = live.latestSnapshot();
        if (newest != latest) {                                   // the engine published a new picture
            latest = newest;
            map.show(latest);
            panel.update(latest);
            if (selectedId != null) {
                panel.showDetails(Selection.describe(latest, selectedId));
            }
        }
        map.animate(nowNanos);                                    // every frame, so the pulse is smooth
    }

    private void applyTheme(Theme next) {
        theme = next;
        root.getStyleClass().removeAll(Theme.LIGHT.cssClass(), Theme.DARK.cssClass());
        root.getStyleClass().add(theme.cssClass());               // the stylesheet colours the panel to match
        map.setTheme(theme);
        panel.showTheme(theme);
    }
}
