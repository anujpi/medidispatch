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
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

/**
 * Draws a {@link WorldSnapshot} as a map: a 2 km grid, patients coloured by how ill they are, units as shapes by
 * kind, and a dashed line from every unit on a trip to its patient.
 * <p>
 * It only draws: it never changes the simulation. Each call to {@link #draw(WorldSnapshot)} repaints everything.
 */
public final class MapView extends Canvas {

    /** Width and height of the map, in pixels (the city is square). */
    static final double SIZE = 640;

    private static final double MARGIN = 24;
    private static final double GRID_KM = 2;
    private static final double UNIT_SIZE = 5;

    private static final Color BACKGROUND = Color.web("#f7f6f2");
    private static final Color GRID = Color.web("#e3e1d9");
    private static final Color TEXT = Color.web("#5f5e5a");
    private static final Color HEALTHY = Color.web("#c9c7bd");
    private static final Color FREE_UNIT = Color.web("#888780");
    private static final Color BUSY_UNIT = Color.web("#2c2c2a");
    private static final Color OFFLINE_UNIT = Color.web("#d3d1c7");
    private static final Color BROKEN = Color.web("#a32d2d");
    private static final Color TRIP_LINE = Color.web("#888780");

    private MapProjection projection;     // made on the first draw, once the city's edges are known

    /** Creates an empty map of {@link #SIZE} by {@link #SIZE} pixels. */
    public MapView() {
        super(SIZE, SIZE);
    }

    /**
     * Repaints the whole map from a snapshot.
     *
     * @param snapshot the city at one moment
     */
    public void draw(WorldSnapshot snapshot) {
        if (projection == null) {
            projection = new MapProjection(snapshot.bounds(), getWidth(), getHeight(), MARGIN);
        }
        GraphicsContext g = getGraphicsContext2D();
        drawBackground(g);
        drawTrips(g, snapshot);           // lines first, so dots and shapes sit on top of them
        drawPatients(g, snapshot);
        drawUnits(g, snapshot);
        drawLegend(g);
    }

    /**
     * The colour for a patient's band.
     *
     * @param band the band of the patient's open case
     * @return green when being treated (LOW), amber, orange or red as it gets worse
     */
    static Color colourOf(RiskBand band) {
        return switch (band) {
            case LOW -> Color.web("#639922");
            case MEDIUM -> Color.web("#ef9f27");
            case HIGH -> Color.web("#d85a30");
            case CRITICAL -> Color.web("#e24b4a");
        };
    }

    // ---------- the layers ----------

    private void drawBackground(GraphicsContext g) {
        g.setFill(BACKGROUND);
        g.fillRect(0, 0, getWidth(), getHeight());
        g.setStroke(GRID);
        g.setLineWidth(1);
        double step = GRID_KM * projection.pixelsPerKm();
        for (double p = MARGIN; p <= getWidth() - MARGIN + 0.5; p += step) {
            g.strokeLine(p, MARGIN, p, getHeight() - MARGIN);          // vertical line
            g.strokeLine(MARGIN, p, getWidth() - MARGIN, p);           // horizontal line
        }
        g.setFill(TEXT);
        g.setFont(Font.font(11));
        g.fillText("grid: 2 km", MARGIN, getHeight() - 8);
    }

    private void drawTrips(GraphicsContext g, WorldSnapshot snapshot) {
        g.setStroke(TRIP_LINE);
        g.setLineWidth(1);
        g.setLineDashes(5, 4);                                        // dashed: 5 px line, 4 px gap
        for (UnitView unit : snapshot.units()) {
            if (unit.target().isPresent() && unit.state() != UnitState.ON_SCENE) {
                Location to = unit.target().get();
                g.strokeLine(x(unit.position()), y(unit.position()), x(to), y(to));
            }
        }
        g.setLineDashes();                                            // back to solid lines
    }

    private void drawPatients(GraphicsContext g, WorldSnapshot snapshot) {
        g.setFont(Font.font(10));
        for (PatientView patient : snapshot.patients()) {
            double x = x(patient.home());
            double y = y(patient.home());
            if (patient.band().isEmpty()) {
                g.setFill(HEALTHY);
                g.fillOval(x - 3, y - 3, 6, 6);                       // small grey dot
            } else {
                g.setFill(colourOf(patient.band().get()));
                g.fillOval(x - 6, y - 6, 12, 12);                     // bigger coloured dot, with its name
                g.setFill(TEXT);
                g.fillText(patient.id(), x + 8, y + 4);
            }
        }
    }

    private void drawUnits(GraphicsContext g, WorldSnapshot snapshot) {
        g.setFont(Font.font(10));
        for (UnitView unit : snapshot.units()) {
            double x = x(unit.position());
            double y = y(unit.position());
            if (unit.state() == UnitState.BROKEN_DOWN) {
                drawCross(g, x, y);
                continue;
            }
            Color colour = switch (unit.state()) {
                case FREE -> FREE_UNIT;
                case OFFLINE -> OFFLINE_UNIT;
                default -> BUSY_UNIT;
            };
            boolean hollow = unit.state() == UnitState.NO_SIGNAL;      // lost signal: dashed outline only
            drawShape(g, unit.type(), x, y, colour, hollow);
            if (unit.state() != UnitState.FREE && unit.state() != UnitState.OFFLINE) {
                g.setFill(TEXT);
                g.fillText(unit.id(), x + 8, y - 6);
            }
        }
    }

    private void drawLegend(GraphicsContext g) {
        double left = getWidth() - 150;
        double top = 10;
        g.setFill(Color.web("#ffffff", 0.9));
        g.fillRoundRect(left, top, 140, 196, 8, 8);
        g.setFont(Font.font(11));
        double y = top + 18;
        y = legendDot(g, left, y, colourOf(RiskBand.CRITICAL), "critical");
        y = legendDot(g, left, y, colourOf(RiskBand.HIGH), "high");
        y = legendDot(g, left, y, colourOf(RiskBand.MEDIUM), "medium");
        y = legendDot(g, left, y, colourOf(RiskBand.LOW), "being treated");
        y = legendDot(g, left, y, HEALTHY, "healthy");
        y = legendShape(g, left, y, ResourceType.AMBULANCE_ALS, "ALS ambulance");
        y = legendShape(g, left, y, ResourceType.AMBULANCE_BLS, "BLS ambulance");
        y = legendShape(g, left, y, ResourceType.NURSE, "nurse");
        y = legendShape(g, left, y, ResourceType.DOCTOR, "doctor");
        drawCross(g, left + 14, y - 4);
        g.setFill(TEXT);
        g.fillText("broken down", left + 28, y);
    }

    // ---------- small helpers ----------

    private double legendDot(GraphicsContext g, double left, double y, Color colour, String text) {
        g.setFill(colour);
        g.fillOval(left + 9, y - 9, 10, 10);
        g.setFill(TEXT);
        g.fillText(text, left + 28, y);
        return y + 18;
    }

    private double legendShape(GraphicsContext g, double left, double y, ResourceType type, String text) {
        drawShape(g, type, left + 14, y - 4, BUSY_UNIT, false);
        g.setFill(TEXT);
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
        g.setStroke(BROKEN);
        g.setLineWidth(2);
        g.strokeLine(x - 5, y - 5, x + 5, y + 5);
        g.strokeLine(x - 5, y + 5, x + 5, y - 5);
    }

    private double x(Location location) {
        return projection.x(location.longitude());
    }

    private double y(Location location) {
        return projection.y(location.latitude());
    }
}
