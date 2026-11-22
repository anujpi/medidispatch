package com.medidispatch.routing;

import java.util.Optional;

/**
 * The kinds of road a vehicle can drive on, from OpenStreetMap's {@code highway} tag, each with its free-flow speed:
 * how fast traffic moves with nobody in the way. The traffic model (later) slows these down by time of day.
 * <p>
 * The order matters to nobody but {@link RoadGraph}, which stores a road's class as its position in this list; a
 * saved graph file therefore records the class names and checks them when it is loaded.
 */
public enum RoadClass {
    MOTORWAY(80),
    TRUNK(60),
    PRIMARY(45),
    SECONDARY(35),
    TERTIARY(30),
    UNCLASSIFIED(25),
    RESIDENTIAL(20),
    LIVING_STREET(10);

    private final int speedKmh;

    RoadClass(int speedKmh) {
        this.speedKmh = speedKmh;
    }

    /** @return the free-flow speed in km/h */
    public int speedKmh() {
        return speedKmh;
    }

    /**
     * Translates OpenStreetMap's {@code highway} tag. A {@code _link} road (a slip road, such as
     * {@code primary_link}) counts as its parent class.
     *
     * @param tag the tag's value, for example {@code "residential"}
     * @return the class, or empty for a way that is not a road for vehicles ({@code footway}, {@code cycleway},
     *         {@code service}, ...) or a missing tag
     */
    public static Optional<RoadClass> fromHighwayTag(String tag) {
        if (tag == null || tag.isBlank()) {
            return Optional.empty();
        }
        String base = tag.endsWith("_link") ? tag.substring(0, tag.length() - "_link".length()) : tag;
        for (RoadClass c : values()) {
            if (c.name().equalsIgnoreCase(base)) {
                return Optional.of(c);
            }
        }
        return Optional.empty();
    }
}
