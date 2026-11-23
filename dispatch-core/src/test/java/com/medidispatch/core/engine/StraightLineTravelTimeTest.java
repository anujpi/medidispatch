package com.medidispatch.core.engine;

import com.medidispatch.core.domain.Location;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class StraightLineTravelTimeTest {

    private final StraightLineTravelTime travel = new StraightLineTravelTime();

    /** 0.09 degrees of latitude is about 10 km: 15 minutes at 40 km/h. */
    @Test
    void tenKilometresTakeAQuarterOfAnHour() {
        Location a = new Location(12.97, 77.59);
        Location b = new Location(12.97 + 0.09, 77.59);

        double seconds = travel.seconds(a, b, false).getAsDouble();

        assertEquals(a.distanceKmTo(b) / 40 * 3600, seconds, 1e-9);
        assertEquals(900, seconds, 10);
    }

    /** A straight line has no traffic to make way for a siren. */
    @Test
    void theSirenMakesNoDifference() {
        Location a = new Location(12.97, 77.59);
        Location b = new Location(13.0, 77.6);

        assertEquals(travel.seconds(a, b, false).getAsDouble(), travel.seconds(a, b, true).getAsDouble());
    }

    @Test
    void missingPointsAreRefused() {
        Location a = new Location(12.97, 77.59);

        assertThrows(IllegalArgumentException.class, () -> travel.seconds(null, a, false));
        assertThrows(IllegalArgumentException.class, () -> travel.seconds(a, null, false));
    }
}
