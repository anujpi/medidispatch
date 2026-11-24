package com.medidispatch.routing;

import com.medidispatch.core.domain.Location;

import java.util.List;

/**
 * A drive from one point to another, as a crew's navigation or a map needs it: the way to go, how long it takes and
 * how far it is. Unlike a {@link Route}, which runs from road node to road node, a drive starts and ends at the exact
 * points asked for (the straight bits to and from the road included).
 *
 * @param points  the way, from the start to the end, at least two points
 * @param seconds how long it takes, in the traffic it was planned in
 * @param metres  how far it is
 */
public record Drive(List<Location> points, double seconds, double metres) {

    /**
     * Takes an unmodifiable copy of the points.
     *
     * @throws IllegalArgumentException if there are fewer than two points or a number is negative
     */
    public Drive {
        if (points == null || points.size() < 2) {
            throw new IllegalArgumentException("a drive has at least a start and an end");
        }
        if (seconds < 0 || metres < 0) {
            throw new IllegalArgumentException("seconds and metres cannot be negative");
        }
        points = List.copyOf(points);
    }
}
