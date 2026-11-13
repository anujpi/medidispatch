package com.medidispatch.sim.fx;

import com.medidispatch.core.domain.Location;
import com.medidispatch.core.domain.ResourceType;
import com.medidispatch.core.domain.RiskBand;
import com.medidispatch.sim.WorldSnapshot;
import com.medidispatch.sim.WorldSnapshot.PatientView;
import com.medidispatch.sim.WorldSnapshot.UnitState;
import com.medidispatch.sim.WorldSnapshot.UnitView;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;

import java.util.function.Consumer;

/**
 * The map: draws a {@link WorldSnapshot} on a canvas that fills the available space, and lets the user move around.
 * <ul>
 *   <li>drag to move the map, scroll to zoom (around the mouse), double-click to see the whole city again;</li>
 *   <li>click a unit or patient to select it: it gets a ring, and the window is told which one
 *       ({@link #setOnSelect(Consumer)});</li>
 *   <li>critical patients pulse, so they catch the eye.</li>
 * </ul>
 * Positions are scaled by the zoom but shapes, lines and text keep their size on screen, so nothing turns into a
 * giant blob when zoomed in. It only draws: it never changes the simulation.
 */
public final class MapView extends Pane {

    private static final double MARGIN = 24;
    private static final double GRID_KM = 2;
    private static final double UNIT_SIZE = 5;
    private static final double PICK_RADIUS = 10;            // how close a click must be to select something
    private static final double ZOOM_STEP = 1.15;
    private static final long PULSE_NANOS = 1_200_000_000L;  // one pulse every 1.2 s

    private final Canvas canvas = new Canvas();
    private final Viewport viewport = new Viewport();
    private Theme theme = Theme.DARK;
    private WorldSnapshot snapshot;

    // the city is square: it is fitted into the biggest square that fits, centred
    private MapProjection projection;
    private double originX;
    private double originY;

    private String selectedId;
    private Consumer<String> onSelect = id -> {};
    private double dragX;
    private double dragY;
    private boolean dragged;
    private long nowNanos;

    /** Creates an empty map that grows and shrinks with the window. */
    public MapView() {
        getChildren().add(canvas);
        setMinSize(320, 320);
        setPrefSize(720, 720);
        canvas.widthProperty().bind(widthProperty());         // the canvas always fills this pane
        canvas.heightProperty().bind(heightProperty());
        widthProperty().addListener((obs, before, after) -> fit());
        heightProperty().addListener((obs, before, after) -> fit());
        Rectangle clip = new Rectangle();                     // nothing is drawn outside the map's area
        clip.widthProperty().bind(widthProperty());
        clip.heightProperty().bind(heightProperty());
        setClip(clip);

        setOnScroll(e -> {
            viewport.zoomAt(e.getX(), e.getY(), e.getDeltaY() > 0 ? ZOOM_STEP : 1 / ZOOM_STEP);
            render();
        });
        setOnMousePressed(e -> {
            dragX = e.getX();
            dragY = e.getY();
            dragged = false;
        });
        setOnMouseDragged(e -> {
            viewport.panBy(e.getX() - dragX, e.getY() - dragY);
            dragX = e.getX();
            dragY = e.getY();
            dragged = true;
            render();
        });
        setOnMouseClicked(e -> {
            if (dragged) {
                return;                                       // the end of a drag is not a click
            }
            if (e.getClickCount() == 2) {
                resetView();
            } else {
                select(pick(e.getX(), e.getY()));
            }
        });
    }

    /**
     * Shows a new moment of the simulation.
     *
     * @param snapshot the city at one moment
     */
    public void show(WorldSnapshot snapshot) {
        boolean first = this.snapshot == null;
        this.snapshot = snapshot;
        if (first) {
            fit();
        }
        render();
    }

    /**
     * Repaints for the current time, so critical patients keep pulsing even while the simulation is paused.
     *
     * @param nowNanos the frame time from the animation timer
     */
    public void animate(long nowNanos) {
        this.nowNanos = nowNanos;
        render();
    }

    /**
     * Switches between the light and the dark colours.
     *
     * @param theme the colours to use
     */
    public void setTheme(Theme theme) {
        this.theme = theme;
        render();
    }

    /** Shows the whole city again. */
    public void resetView() {
        viewport.reset();
        render();
    }

    /**
     * Sets what happens when the user clicks a unit or a patient.
     *
     * @param action receives the clicked id, or {@code null} when empty map was clicked
     */
    public void setOnSelect(Consumer<String> action) {
        this.onSelect = action;
    }

    // ---------- layout ----------

    // fits the square city into the current size of the pane
    private void fit() {
        if (snapshot == null) {
            return;
        }
        double side = Math.min(getWidth(), getHeight());
        if (side <= 2 * MARGIN + 1) {
            return;                                           // not laid out yet
        }
        projection = new MapProjection(snapshot.bounds(), side, side, MARGIN);
        originX = (getWidth() - side) / 2;
        originY = (getHeight() - side) / 2;
        render();
    }

    private double screenX(Location location) {
        return viewport.toScreenX(originX + projection.x(location.longitude()));
    }

    private double screenY(Location location) {
        return viewport.toScreenY(originY + projection.y(location.latitude()));
    }

    // ---------- selection ----------

    // the unit or patient closest to the click, if one is near enough
    private String pick(double x, double y) {
        if (snapshot == null || projection == null) {
            return null;
        }
        String best = null;
        double bestDistance = PICK_RADIUS;
        for (UnitView unit : snapshot.units()) {              // units first: they sit on top
            double d = Math.hypot(screenX(unit.position()) - x, screenY(unit.position()) - y);
            if (d <= bestDistance) {
                best = unit.id();
                bestDistance = d;
            }
        }
        if (best != null) {
            return best;
        }
        for (PatientView patient : snapshot.patients()) {
            double d = Math.hypot(screenX(patient.home()) - x, screenY(patient.home()) - y);
            if (d <= bestDistance) {
                best = patient.id();
                bestDistance = d;
            }
        }
        return best;
    }

    private void select(String id) {
        selectedId = id;
        onSelect.accept(id);
        render();
    }

    // ---------- drawing ----------

    private void render() {
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.setFill(theme.background());
        g.fillRect(0, 0, getWidth(), getHeight());
        if (snapshot == null || projection == null) {
            return;
        }
        drawGrid(g);
        drawTrips(g);                     // lines first, so dots and shapes sit on top of them
        drawPatients(g);
        drawUnits(g);
        drawSelection(g);
        drawLegend(g);
        drawInfo(g);
    }

    private void drawGrid(GraphicsContext g) {
        double size = projection.y(snapshot.bounds().minLatitude()) - MARGIN;   // the city's side in base pixels
        double step = GRID_KM * projection.pixelsPerKm();
        g.setStroke(theme.grid());
        g.setLineWidth(1);
        for (double p = 0; p <= size + 0.5; p += step) {
            double x = viewport.toScreenX(originX + MARGIN + p);
            double y = viewport.toScreenY(originY + MARGIN + p);
            g.strokeLine(x, viewport.toScreenY(originY + MARGIN), x, viewport.toScreenY(originY + MARGIN + size));
            g.strokeLine(viewport.toScreenX(originX + MARGIN), y, viewport.toScreenX(originX + MARGIN + size), y);
        }
    }

    private void drawTrips(GraphicsContext g) {
        g.setStroke(theme.tripLine());
        g.setLineWidth(1);
        g.setLineDashes(5, 4);                                        // dashed: 5 px line, 4 px gap
        for (UnitView unit : snapshot.units()) {
            if (unit.target().isPresent() && unit.state() != UnitState.ON_SCENE) {
                Location to = unit.target().get();
                g.strokeLine(screenX(unit.position()), screenY(unit.position()), screenX(to), screenY(to));
            }
        }
        g.setLineDashes();                                            // back to solid lines
    }

    private void drawPatients(GraphicsContext g) {
        g.setFont(Font.font(10));
        double phase = (nowNanos % PULSE_NANOS) / (double) PULSE_NANOS;   // 0 to 1, then again
        for (PatientView patient : snapshot.patients()) {
            double x = screenX(patient.home());
            double y = screenY(patient.home());
            if (patient.band().isEmpty()) {
                g.setFill(theme.healthy());
                g.fillOval(x - 3, y - 3, 6, 6);                       // small dot
                continue;
            }
            Color colour = theme.band(patient.band().get());
            if (patient.band().get() == RiskBand.CRITICAL) {          // a ring that grows and fades
                double r = 7 + 12 * phase;
                g.setStroke(new Color(colour.getRed(), colour.getGreen(), colour.getBlue(), 1 - phase));
                g.setLineWidth(2);
                g.strokeOval(x - r, y - r, 2 * r, 2 * r);
            }
            g.setFill(colour);
            g.fillOval(x - 6, y - 6, 12, 12);
            g.setFill(theme.text());
            g.fillText(patient.id(), x + 8, y + 4);
        }
    }

    private void drawUnits(GraphicsContext g) {
        g.setFont(Font.font(10));
        for (UnitView unit : snapshot.units()) {
            double x = screenX(unit.position());
            double y = screenY(unit.position());
            if (unit.state() == UnitState.BROKEN_DOWN) {
                drawCross(g, x, y);
                continue;
            }
            Color colour = switch (unit.state()) {
                case FREE -> theme.freeUnit();
                case OFFLINE -> theme.offlineUnit();
                default -> theme.busyUnit();
            };
            boolean hollow = unit.state() == UnitState.NO_SIGNAL;      // lost signal: dashed outline only
            drawShape(g, unit.type(), x, y, colour, hollow);
            if (unit.state() != UnitState.FREE && unit.state() != UnitState.OFFLINE) {
                g.setFill(theme.text());
                g.fillText(unit.id(), x + 8, y - 6);
            }
        }
    }

    private void drawSelection(GraphicsContext g) {
        if (selectedId == null) {
            return;
        }
        Location where = null;
        for (UnitView unit : snapshot.units()) {
            if (unit.id().equals(selectedId)) {
                where = unit.position();
            }
        }
        for (PatientView patient : snapshot.patients()) {
            if (patient.id().equals(selectedId)) {
                where = patient.home();
            }
        }
        if (where == null) {
            return;
        }
        g.setStroke(theme.selection());
        g.setLineWidth(2);
        g.strokeOval(screenX(where) - 11, screenY(where) - 11, 22, 22);
    }

    private void drawLegend(GraphicsContext g) {
        double left = getWidth() - 150;
        double top = 10;
        g.setFill(theme.legendBackground());
        g.fillRoundRect(left, top, 140, 196, 8, 8);
        g.setFont(Font.font(11));
        double y = top + 18;
        y = legendDot(g, left, y, theme.band(RiskBand.CRITICAL), "critical");
        y = legendDot(g, left, y, theme.band(RiskBand.HIGH), "high");
        y = legendDot(g, left, y, theme.band(RiskBand.MEDIUM), "medium");
        y = legendDot(g, left, y, theme.band(RiskBand.LOW), "being treated");
        y = legendDot(g, left, y, theme.healthy(), "healthy");
        y = legendShape(g, left, y, ResourceType.AMBULANCE_ALS, "ALS ambulance");
        y = legendShape(g, left, y, ResourceType.AMBULANCE_BLS, "BLS ambulance");
        y = legendShape(g, left, y, ResourceType.NURSE, "nurse");
        y = legendShape(g, left, y, ResourceType.DOCTOR, "doctor");
        drawCross(g, left + 14, y - 4);
        g.setFill(theme.text());
        g.fillText("broken down", left + 28, y);
    }

    private void drawInfo(GraphicsContext g) {
        g.setFill(theme.text());
        g.setFont(Font.font(11));
        g.fillText(String.format("grid: 2 km    zoom: %.1fx", viewport.zoom()), 10, getHeight() - 10);
    }

    // ---------- small helpers ----------

    private double legendDot(GraphicsContext g, double left, double y, Color colour, String text) {
        g.setFill(colour);
        g.fillOval(left + 9, y - 9, 10, 10);
        g.setFill(theme.text());
        g.fillText(text, left + 28, y);
        return y + 18;
    }

    private double legendShape(GraphicsContext g, double left, double y, ResourceType type, String text) {
        drawShape(g, type, left + 14, y - 4, theme.busyUnit(), false);
        g.setFill(theme.text());
        g.fillText(text, left + 28, y);
        return y + 18;
    }

    private void drawShape(GraphicsContext g, ResourceType type, double x, double y, Color colour, boolean hollow) {
        double r = UNIT_SIZE;
        g.setFill(colour);
        g.setStroke(colour);
        g.setLineWidth(1.5);
        if (hollow) {
            g.setLineDashes(2, 2);
        }
        switch (type) {
            case AMBULANCE_ALS -> {
                if (hollow) {
                    g.strokeRect(x - r, y - r, 2 * r, 2 * r);
                } else {
                    g.fillRect(x - r, y - r, 2 * r, 2 * r);           // filled square
                }
            }
            case AMBULANCE_BLS -> g.strokeRect(x - r, y - r, 2 * r, 2 * r);   // always a hollow square
            case NURSE -> polygon(g, new double[]{x, x + r + 1, x - r - 1},
                                     new double[]{y - r - 1, y + r, y + r}, hollow);          // triangle
            case DOCTOR -> polygon(g, new double[]{x, x + r + 1, x, x - r - 1},
                                      new double[]{y - r - 1, y, y + r + 1, y}, hollow);      // diamond
            case COURIER -> g.strokeOval(x - r, y - r, 2 * r, 2 * r);
        }
        g.setLineDashes();
    }

    private void polygon(GraphicsContext g, double[] xs, double[] ys, boolean hollow) {
        if (hollow) {
            g.strokePolygon(xs, ys, xs.length);
        } else {
            g.fillPolygon(xs, ys, xs.length);
        }
    }

    private void drawCross(GraphicsContext g, double x, double y) {
        g.setStroke(theme.broken());
        g.setLineWidth(2);
        g.strokeLine(x - 5, y - 5, x + 5, y + 5);
        g.strokeLine(x - 5, y + 5, x + 5, y - 5);
    }
}
