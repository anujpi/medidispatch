package com.medidispatch.sim.fx;

import com.medidispatch.sim.WorldSnapshot.CityBounds;

/**
 * Turns map coordinates (latitude, longitude) into pixel positions on a drawing area, with north at the top.
 * <p>
 * Pure arithmetic with no JavaFX in it, so it is easy to test; the drawing code just asks it where things go.
 */
public final class MapProjection {

    private static final double KM_PER_DEGREE_LATITUDE = 111.32;

    private final CityBounds bounds;
    private final double width;
    private final double height;
    private final double margin;

    /**
     * Creates a projection of the city onto a drawing area.
     *
     * @param bounds the edges of the city
     * @param width  the drawing area's width in pixels
     * @param height the drawing area's height in pixels
     * @param margin empty space kept around the city, in pixels
     * @throws IllegalArgumentException if the bounds are missing or have no size, or the area is not bigger than
     *                                  its margins
     */
    public MapProjection(CityBounds bounds, double width, double height, double margin) {
        if (bounds == null) {
            throw new IllegalArgumentException("bounds are required");
        }
        if (bounds.maxLatitude() <= bounds.minLatitude() || bounds.maxLongitude() <= bounds.minLongitude()) {
            throw new IllegalArgumentException("the bounds must have a size");
        }
        if (margin < 0 || width <= 2 * margin || height <= 2 * margin) {
            throw new IllegalArgumentException("the drawing area must be bigger than its margins");
        }
        this.bounds = bounds;
        this.width = width;
        this.height = height;
        this.margin = margin;
    }

    /**
     * Where a longitude goes across the drawing area.
     *
     * @param longitude east-west position
     * @return the pixel x, {@code margin} at the western edge and {@code width - margin} at the eastern edge
     */
    public double x(double longitude) {
        double share = (longitude - bounds.minLongitude()) / (bounds.maxLongitude() - bounds.minLongitude());
        return margin + share * (width - 2 * margin);
    }

    /**
     * Where a latitude goes down the drawing area. North is at the top, so higher latitudes get smaller y.
     *
     * @param latitude north-south position
     * @return the pixel y, {@code margin} at the northern edge and {@code height - margin} at the southern edge
     */
    public double y(double latitude) {
        double share = (bounds.maxLatitude() - latitude) / (bounds.maxLatitude() - bounds.minLatitude());
        return margin + share * (height - 2 * margin);
    }

    /**
     * How many pixels one kilometre takes, for drawing the grid and the scale.
     *
     * @return pixels per kilometre (north-south)
     */
    public double pixelsPerKm() {
        double heightKm = (bounds.maxLatitude() - bounds.minLatitude()) * KM_PER_DEGREE_LATITUDE;
        return (height - 2 * margin) / heightKm;
    }
}
