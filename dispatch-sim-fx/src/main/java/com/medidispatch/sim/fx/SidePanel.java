package com.medidispatch.sim.fx;

import com.medidispatch.sim.SimulationReport;
import com.medidispatch.sim.WorldSnapshot;
import com.medidispatch.sim.WorldSnapshot.PatientView;
import com.medidispatch.sim.WorldSnapshot.UnitState;
import com.medidispatch.sim.WorldSnapshot.UnitView;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.function.IntConsumer;

/**
 * The panel next to the map: the simulated clock, Play/Pause, Step and speed buttons, live counters, the event
 * log, and the final report when the run ends.
 * <p>
 * It never touches the simulation. Buttons only call the actions the window hands in
 * ({@link #setOnPlayPause(Runnable)}, {@link #setOnStep(Runnable)}, {@link #setOnSpeed(IntConsumer)}), and
 * {@link #update(WorldSnapshot)} only shows what a snapshot says.
 */
public final class SidePanel extends VBox {

    /** The speeds offered, in simulated seconds per real second. */
    static final int[] SPEEDS = {10, 60, 300};

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC);

    private final Label clock = new Label("--:--:--");
    private final Button playPause = new Button("Pause");
    private final Button step = new Button("Step");
    private final ToggleGroup speedGroup = new ToggleGroup();
    private final Label openCases = value();
    private final Label waiting = value();
    private final Label busyUnits = value();
    private final Label offlineUnits = value();
    private final Label upgrades = value();
    private final Label timeouts = value();
    private final Label failures = value();
    private final Label responders = value();
    private final ListView<String> log = new ListView<>();
    private final TextArea report = new TextArea();

    // what the buttons do; the window sets these
    private Runnable onPlayPause = () -> {};
    private Runnable onStep = () -> {};
    private IntConsumer onSpeed = speed -> {};

    /**
     * Builds the panel.
     *
     * @param initialSpeed the speed button selected at the start; one of {@link #SPEEDS}
     */
    public SidePanel(int initialSpeed) {
        getStyleClass().add("side-panel");
        setPrefWidth(300);
        clock.getStyleClass().add("clock");

        playPause.setOnAction(e -> onPlayPause.run());
        step.setOnAction(e -> onStep.run());
        HBox controls = new HBox(8, playPause, step);

        HBox speeds = new HBox(4);
        for (int speed : SPEEDS) {
            ToggleButton button = new ToggleButton(speed + "x");
            button.setToggleGroup(speedGroup);                    // only one in the group can be selected
            button.setSelected(speed == initialSpeed);
            button.setOnAction(e -> {
                button.setSelected(true);                         // clicking the selected one keeps it selected
                onSpeed.accept(speed);
            });
            speeds.getChildren().add(button);
        }

        GridPane counters = new GridPane();
        counters.setHgap(12);
        counters.setVgap(4);
        addCounter(counters, 0, 0, "Open cases", openCases);
        addCounter(counters, 2, 0, "Waiting", waiting);
        addCounter(counters, 0, 1, "Units busy", busyUnits);
        addCounter(counters, 2, 1, "Offline", offlineUnits);
        addCounter(counters, 0, 2, "Upgrades", upgrades);
        addCounter(counters, 2, 2, "First responders", responders);
        addCounter(counters, 0, 3, "Timeouts", timeouts);
        addCounter(counters, 2, 3, "Failures", failures);

        log.getStyleClass().add("event-log");
        log.setPrefHeight(300);
        log.setFocusTraversable(false);

        report.getStyleClass().add("report");
        report.setEditable(false);
        report.setPrefRowCount(16);
        showNode(report, false);                                  // appears when the run ends

        getChildren().addAll(clock, controls,
                sectionTitle("Speed (simulated seconds per real second)"), speeds,
                sectionTitle("Now"), counters,
                sectionTitle("What the engine just did"), log, report);
    }

    /**
     * Sets what happens when Play/Pause is clicked.
     *
     * @param action the action to run
     */
    public void setOnPlayPause(Runnable action) {
        this.onPlayPause = action;
    }

    /**
     * Sets what happens when Step is clicked.
     *
     * @param action the action to run
     */
    public void setOnStep(Runnable action) {
        this.onStep = action;
    }

    /**
     * Sets what happens when a speed button is clicked.
     *
     * @param action receives the chosen speed, in simulated seconds per real second
     */
    public void setOnSpeed(IntConsumer action) {
        this.onSpeed = action;
    }

    /**
     * Shows whether the simulation is playing, on the Play/Pause button.
     *
     * @param playing {@code true} while playing
     */
    public void showPlaying(boolean playing) {
        playPause.setText(playing ? "Pause" : "Play");
    }

    /**
     * Shows what a snapshot says: the clock, the counters and the latest events, and the report once the run
     * has finished.
     *
     * @param snapshot the city at one moment
     */
    public void update(WorldSnapshot snapshot) {
        clock.setText(CLOCK.format(snapshot.time()));

        int open = 0;
        for (PatientView patient : snapshot.patients()) {
            if (patient.band().isPresent()) {
                open++;
            }
        }
        int busy = 0;
        int offline = 0;
        for (UnitView unit : snapshot.units()) {
            if (unit.state() == UnitState.OFFLINE) {
                offline++;
            } else if (unit.state() != UnitState.FREE) {
                busy++;
            }
        }
        SimulationReport r = snapshot.report();
        openCases.setText(String.valueOf(open));
        waiting.setText(String.valueOf(snapshot.waitingCases()));
        busyUnits.setText(busy + " / " + snapshot.units().size());
        offlineUnits.setText(String.valueOf(offline));
        upgrades.setText(String.valueOf(r.upgradedResponses()));
        responders.setText(String.valueOf(r.firstRespondersSent()));
        timeouts.setText(String.valueOf(r.timeouts()));
        failures.setText(String.valueOf(r.failures()));

        if (!log.getItems().equals(snapshot.recentEvents())) {   // only touch the list when it changed
            log.getItems().setAll(snapshot.recentEvents());
        }

        if (snapshot.finished() && !report.isVisible()) {
            report.setText(r.toText());
            showNode(log, false);                                  // the report takes the log's place
            showNode(report, true);
            playPause.setText("Finished");
            playPause.setDisable(true);
            step.setDisable(true);
        }
    }

    // ---------- helpers ----------

    private static Label value() {
        Label label = new Label("0");
        label.getStyleClass().add("counter-value");
        return label;
    }

    private static Label sectionTitle(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("section-title");
        return label;
    }

    private static void addCounter(GridPane grid, int column, int row, String name, Label value) {
        Label label = new Label(name);
        label.getStyleClass().add("counter-name");
        grid.add(label, column, row);
        grid.add(value, column + 1, row);
    }

    // hidden nodes also give up their space in the layout
    private static void showNode(javafx.scene.Node node, boolean show) {
        node.setVisible(show);
        node.setManaged(show);
    }
}
