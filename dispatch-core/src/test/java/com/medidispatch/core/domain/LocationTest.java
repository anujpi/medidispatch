package com.medidispatch.core.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class LocationTest {

    //validation

    @Test
    void validLocationIsAccepted() {
        assertDoesNotThrow(() -> new Location(12.9716, 77.5946));
    }

    @Test
    void boundaryValuesAreAccepted() {
        assertDoesNotThrow(() -> new Location(90, 180));
        assertDoesNotThrow(() -> new Location(-90, -180));
        assertDoesNotThrow(() -> new Location(0, 0));
    }

    @Test
    void latitudeOutOfRangeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Location(90.1, 0));
        assertThrows(IllegalArgumentException.class, () -> new Location(-90.1, 0));
    }

    @Test
    void longitudeOutOfRangeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Location(0, 180.1));
        assertThrows(IllegalArgumentException.class, () -> new Location(0, -180.1));
    }

    //distance: properties

    @Test
    void distanceToTheSamePointIsZero() {
        Location a = new Location(12.9716, 77.5946);

        assertEquals(0.0, a.distanceKmTo(a), 0.0001);
    }

    @Test
    void distanceIsSymmetric() {
        Location a = new Location(12.9716, 77.5946);
        Location b = new Location(13.0827, 80.2707);

        assertEquals(a.distanceKmTo(b), b.distanceKmTo(a), 0.0001);
    }

    @Test
    void distanceIsNeverNegative() {
        Location a = new Location(-33.8688, 151.2093);
        Location b = new Location(51.5074, -0.1278);

        assertTrue(a.distanceKmTo(b) > 0);
    }

    //distance: known values

    @Test
    void oneDegreeOfLongitudeOnTheEquatorIsAbout111Km() {
        double d = new Location(0, 0).distanceKmTo(new Location(0, 1));

        assertEquals(111.19, d, 0.1);
    }

    @Test
    void oneDegreeOfLatitudeIsAbout111Km() {
        double d = new Location(0, 0).distanceKmTo(new Location(1, 0));

        assertEquals(111.19, d, 0.1);
    }

    @Test
    void oneDegreeOfLongitudeAtLatitude60IsHalfAsFar() {   // catches missing radians in the cos term
        double d = new Location(60, 0).distanceKmTo(new Location(60, 1));

        assertEquals(55.6, d, 0.1);
    }

    @Test
    void halfwayAroundTheEarthIsHalfTheCircumference() {   // antipodal points, a == 1
        double d = new Location(0, 0).distanceKmTo(new Location(0, 180));

        assertEquals(20015.09, d, 1.0);
    }

    @Test
    void bangaloreToChennaiIsAbout290Km() {
        Location bangalore = new Location(12.9716, 77.5946);
        Location chennai = new Location(13.0827, 80.2707);

        assertEquals(290.0, bangalore.distanceKmTo(chennai), 2.0);
    }
}