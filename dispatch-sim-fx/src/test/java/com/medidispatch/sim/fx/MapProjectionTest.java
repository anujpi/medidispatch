package com.medidispatch.sim.fx;

import com.medidispatch.sim.WorldSnapshot.CityBounds;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class MapProjectionTest {

    private static final double DELTA = 1e-9;

    // a city from latitude 10.0 to 10.2 and longitude 20.0 to 20.4, drawn on 440 x 440 pixels with a 20 px margin
    private static final CityBounds CITY = new CityBounds(10.0, 20.0, 10.2, 20.4);
    private final MapProjection projection = new MapProjection(CITY, 440, 440, 20);

    @Test
    void theNorthWestCornerIsAtTheTopLeftInsideTheMargin() {
        assertEquals(20, projection.x(20.0), DELTA);
        assertEquals(20, projection.y(10.2), DELTA);
    }

    @Test
    void theSouthEastCornerIsAtTheBottomRightInsideTheMargin() {
        assertEquals(420, projection.x(20.4), DELTA);
        assertEquals(420, projection.y(10.0), DELTA);
    }

    @Test
    void theCentreOfTheCityIsTheCentreOfTheDrawing() {
        assertEquals(220, projection.x(20.2), DELTA);
        assertEquals(220, projection.y(10.1), DELTA);
    }

    @Test
    void northIsUp() {
        assertTrue(projection.y(10.15) < projection.y(10.05));     // further north, higher on screen
    }

    @Test
    void pixelsPerKmMatchesTheCitysSize() {
        double cityHeightKm = 0.2 * 111.32;                        // 0.2 degrees of latitude

        assertEquals(400 / cityHeightKm, projection.pixelsPerKm(), DELTA);
    }

    @Test
    void badInputIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new MapProjection(null, 440, 440, 20));
        assertThrows(IllegalArgumentException.class,
                () -> new MapProjection(new CityBounds(10.0, 20.0, 10.0, 20.4), 440, 440, 20));   // no height
        assertThrows(IllegalArgumentException.class, () -> new MapProjection(CITY, 40, 440, 20));  // no room
        assertThrows(IllegalArgumentException.class, () -> new MapProjection(CITY, 440, 440, -1));
    }
}
