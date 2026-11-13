package com.medidispatch.sim.fx;

/**
 * The pan and zoom of the map: which part of the map is on screen, and how big.
 * <p>
 * The map is first laid out at zoom 1 ("base" pixels); the viewport then turns base pixels into screen pixels with
 * {@code screen = base * zoom + offset}. Zooming keeps the point under the mouse still, like an online map.
 * <p>
 * Pure arithmetic with no JavaFX in it, so it is easy to test; the same maths is used for drawing and for working
 * out what was clicked.
 */
public final class Viewport {

    /** No zooming out past the whole city. */
    static final double MIN_ZOOM = 1.0;
    /** At most 8 times closer. */
    static final double MAX_ZOOM = 8.0;

    private double zoom = MIN_ZOOM;
    private double offsetX;
    private double offsetY;

    /**
     * Where a base x ends up on screen.
     *
     * @param baseX x at zoom 1
     * @return x on screen
     */
    public double toScreenX(double baseX) {
        return baseX * zoom + offsetX;
    }

    /**
     * Where a base y ends up on screen.
     *
     * @param baseY y at zoom 1
     * @return y on screen
     */
    public double toScreenY(double baseY) {
        return baseY * zoom + offsetY;
    }

    /**
     * Which base x is at a screen x (the reverse of {@link #toScreenX(double)}).
     *
     * @param screenX x on screen
     * @return x at zoom 1
     */
    public double toBaseX(double screenX) {
        return (screenX - offsetX) / zoom;
    }

    /**
     * Which base y is at a screen y (the reverse of {@link #toScreenY(double)}).
     *
     * @param screenY y on screen
     * @return y at zoom 1
     */
    public double toBaseY(double screenY) {
        return (screenY - offsetY) / zoom;
    }

    /**
     * Zooms in or out around a point on screen; that point stays where it is.
     *
     * @param screenX the point to zoom around (usually the mouse), x
     * @param screenY the point to zoom around, y
     * @param factor  more than 1 zooms in, less than 1 zooms out; the result stays between
     *                {@link #MIN_ZOOM} and {@link #MAX_ZOOM}
     * @throws IllegalArgumentException if {@code factor} is not positive
     */
    public void zoomAt(double screenX, double screenY, double factor) {
        if (factor <= 0) {
            throw new IllegalArgumentException("factor must be positive, got " + factor);
        }
        double baseX = toBaseX(screenX);                      // the map point under the mouse...
        double baseY = toBaseY(screenY);
        zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom * factor));
        offsetX = screenX - baseX * zoom;                     // ...is put back under the mouse
        offsetY = screenY - baseY * zoom;
    }

    /**
     * Moves the map, for dragging.
     *
     * @param dx how far to move right, in screen pixels
     * @param dy how far to move down, in screen pixels
     */
    public void panBy(double dx, double dy) {
        offsetX += dx;
        offsetY += dy;
    }

    /** Back to the whole city, not moved. */
    public void reset() {
        zoom = MIN_ZOOM;
        offsetX = 0;
        offsetY = 0;
    }

    /**
     * The current zoom.
     *
     * @return between {@link #MIN_ZOOM} and {@link #MAX_ZOOM}
     */
    public double zoom() {
        return zoom;
    }
}
