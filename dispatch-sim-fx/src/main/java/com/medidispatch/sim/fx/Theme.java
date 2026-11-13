package com.medidispatch.sim.fx;

import com.medidispatch.core.domain.RiskBand;
import javafx.scene.paint.Color;

/**
 * The colours of the map, in a light and a dark version.
 * <p>
 * Drawing code asks the theme for "the grid colour" or "the colour of a CRITICAL patient" and never hard-codes a
 * colour, so switching themes is just handing the map another theme. The side panel follows the same switch
 * through the CSS class named by {@link #cssClass()}.
 */
public enum Theme {

    /** Daylight: a light map with dark text. */
    LIGHT("light", "#f7f6f2", "#e3e1d9", "#5f5e5a", "#c9c7bd", "#888780", "#2c2c2a", "#d3d1c7", "#a32d2d",
          "#888780", "#ffffff", "#185fa5"),

    /** Night control room: a dark map where the ill patients and busy units stand out. */
    DARK("dark", "#14161a", "#262a31", "#b4b2a9", "#4a4d55", "#7c7f87", "#e8e6df", "#3a3d44", "#f09595",
         "#6a6d75", "#1e2127", "#85b7eb");

    private final String cssClass;
    private final Color background;
    private final Color grid;
    private final Color text;
    private final Color healthy;
    private final Color freeUnit;
    private final Color busyUnit;
    private final Color offlineUnit;
    private final Color broken;
    private final Color tripLine;
    private final Color legendBackground;
    private final Color selection;

    Theme(String cssClass, String background, String grid, String text, String healthy, String freeUnit,
          String busyUnit, String offlineUnit, String broken, String tripLine, String legendBackground,
          String selection) {
        this.cssClass = cssClass;
        this.background = Color.web(background);
        this.grid = Color.web(grid);
        this.text = Color.web(text);
        this.healthy = Color.web(healthy);
        this.freeUnit = Color.web(freeUnit);
        this.busyUnit = Color.web(busyUnit);
        this.offlineUnit = Color.web(offlineUnit);
        this.broken = Color.web(broken);
        this.tripLine = Color.web(tripLine);
        this.legendBackground = Color.web(legendBackground, 0.9);
        this.selection = Color.web(selection);
    }

    /**
     * The other theme, for the light/dark toggle.
     *
     * @return {@link #DARK} for {@link #LIGHT}, and the other way round
     */
    public Theme other() {
        return this == LIGHT ? DARK : LIGHT;
    }

    /**
     * The colour of a patient whose case is in this band. The same in both themes, so a colour always means the
     * same thing.
     *
     * @param band the band of the patient's open case
     * @return green when being treated (LOW), amber, orange or red as it gets worse
     */
    public Color band(RiskBand band) {
        return switch (band) {
            case LOW -> Color.web("#639922");
            case MEDIUM -> Color.web("#ef9f27");
            case HIGH -> Color.web("#d85a30");
            case CRITICAL -> Color.web("#e24b4a");
        };
    }

    /** The CSS class put on the window's root, so the stylesheet can colour the panel to match. */
    public String cssClass()        { return cssClass; }
    public Color background()       { return background; }
    public Color grid()             { return grid; }
    public Color text()             { return text; }
    public Color healthy()          { return healthy; }
    public Color freeUnit()         { return freeUnit; }
    public Color busyUnit()         { return busyUnit; }
    public Color offlineUnit()      { return offlineUnit; }
    public Color broken()           { return broken; }
    public Color tripLine()         { return tripLine; }
    public Color legendBackground() { return legendBackground; }
    public Color selection()        { return selection; }
}
