package com.medidispatch.sim;

import com.medidispatch.core.domain.Location;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

public class CityMapTest {

    @Test
    void randomPointsStayInsideTheCity() {
        Random random = new Random(1);
        double halfDiagonalKm = CityMap.SIZE_KM / 2 * Math.sqrt(2);

        for (int i = 0; i < 1000; i++) {
            Location p = CityMap.randomPoint(random);
            assertTrue(CityMap.CENTER.distanceKmTo(p) <= halfDiagonalKm + 0.01, "outside the city: " + p);
        }
    }

    @Test
    void theSameSeedGivesTheSamePoints() {
        assertEquals(CityMap.randomPoint(new Random(7)), CityMap.randomPoint(new Random(7)));
    }

    @Test
    void movingTowardsAPointCoversTheGivenDistance() {
        Location from = new Location(12.9, 77.5);
        Location to = new Location(13.0, 77.5);                     // about 11 km north

        Location after = CityMap.towards(from, to, 2.0);

        assertEquals(2.0, from.distanceKmTo(after), 0.01);
        assertEquals(from.distanceKmTo(to) - 2.0, after.distanceKmTo(to), 0.01);
    }

    @Test
    void movingFurtherThanTheDestinationStopsExactlyOnIt() {
        Location from = new Location(12.9, 77.5);
        Location to = new Location(12.91, 77.5);                    // about 1.1 km

        assertEquals(to, CityMap.towards(from, to, 5.0));
    }
}
