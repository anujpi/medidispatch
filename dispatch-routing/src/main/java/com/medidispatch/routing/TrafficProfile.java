package com.medidispatch.routing;

import java.time.LocalTime;

/**
 * How traffic slows roads down over a day: a speed factor per road class for any time.
 * <p>
 * The curve is given for MAIN roads (trunk, primary, secondary), one factor per hour; smaller roads get part of the
 * slowdown ({@link #share}), because traffic piles onto the main roads: factor = 1 - (1 - main) x share. At 18:00
 * (main 0.4) a primary road drives at 0.4 of its free-flow speed, a tertiary road at 0.64, a residential lane at 0.82.
 * Between whole hours the factor is interpolated linearly, so a route at 8:59 and one at 9:00 do not differ suddenly.
 * <p>
 * Factors are above 0 and at most 1: traffic only ever slows (which is also what keeps A*'s estimate safe).
 */
// ponytail: one weekday curve; weekends, festivals and rain are other profiles, added here when needed.
public final class TrafficProfile {

    // Bangalore on a weekday: free at night, the morning peak at 9, a busy afternoon, the evening peak at 18
    private static final double[] BANGALORE_WEEKDAY_MAIN_ROADS = {
            1.0, 1.0, 1.0, 1.0, 1.0, 1.0,                     // 00-05
            0.9, 0.75, 0.55, 0.45, 0.5, 0.65,                 // 06-11
            0.7, 0.7, 0.7, 0.7, 0.65, 0.5,                    // 12-17
            0.4, 0.45, 0.6, 0.75, 0.9, 1.0                    // 18-23
    };

    private final double[] mainRoads;

    /**
     * @param mainRoadsByHour main roads' factor for each hour, 0 to 23; copied
     * @throws IllegalArgumentException if there are not 24 factors or one is not above 0 and at most 1
     */
    TrafficProfile(double[] mainRoadsByHour) {
        if (mainRoadsByHour == null || mainRoadsByHour.length != 24) {
            throw new IllegalArgumentException("one factor per hour is required (24)");
        }
        for (double f : mainRoadsByHour) {
            if (!(f > 0 && f <= 1)) {
                throw new IllegalArgumentException("factors must be above 0 and at most 1, got " + f);
            }
        }
        this.mainRoads = mainRoadsByHour.clone();
    }

    /** @return a weekday in Bangalore (times are Bangalore time, see {@link TrafficModel#BANGALORE}) */
    public static TrafficProfile bangaloreWeekday() {
        return new TrafficProfile(BANGALORE_WEEKDAY_MAIN_ROADS);
    }

    /**
     * @param roadClass the kind of road
     * @param time      the local time of day
     * @return the share of its free-flow speed traffic allows, above 0 and at most 1
     */
    public double factor(RoadClass roadClass, LocalTime time) {
        double hours = time.toSecondOfDay() / 3600.0;         // 9:30 -> 9.5
        int hour = (int) hours;
        double past = hours - hour;
        double main = mainRoads[hour] + (mainRoads[(hour + 1) % 24] - mainRoads[hour]) * past;
        return 1 - (1 - main) * share(roadClass);
    }

    /**
     * How much of the main-road slowdown a kind of road gets. Every class is listed (no default), so adding a road
     * class without deciding this is a compile error.
     *
     * @param roadClass the kind of road
     * @return 1 for main roads, less for roads traffic avoids
     */
    static double share(RoadClass roadClass) {
        return switch (roadClass) {
            case TRUNK, PRIMARY, SECONDARY -> 1.0;
            case MOTORWAY -> 0.8;
            case TERTIARY, UNCLASSIFIED -> 0.6;
            case RESIDENTIAL, LIVING_STREET -> 0.3;
        };
    }
}
