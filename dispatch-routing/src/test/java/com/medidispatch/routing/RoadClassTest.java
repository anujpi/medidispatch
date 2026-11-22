package com.medidispatch.routing;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class RoadClassTest {

    @Test
    void drivableTagsAreRecognised() {
        assertEquals(Optional.of(RoadClass.PRIMARY), RoadClass.fromHighwayTag("primary"));
        assertEquals(Optional.of(RoadClass.RESIDENTIAL), RoadClass.fromHighwayTag("residential"));
        assertEquals(Optional.of(RoadClass.LIVING_STREET), RoadClass.fromHighwayTag("living_street"));
    }

    /** A slip road counts as the road it belongs to. */
    @Test
    void aLinkRoadCountsAsItsParent() {
        assertEquals(Optional.of(RoadClass.MOTORWAY), RoadClass.fromHighwayTag("motorway_link"));
        assertEquals(Optional.of(RoadClass.TERTIARY), RoadClass.fromHighwayTag("tertiary_link"));
    }

    @Test
    void waysThatAreNotForVehiclesAreSkipped() {
        assertEquals(Optional.empty(), RoadClass.fromHighwayTag("footway"));
        assertEquals(Optional.empty(), RoadClass.fromHighwayTag("cycleway"));
        assertEquals(Optional.empty(), RoadClass.fromHighwayTag("service"));
        assertEquals(Optional.empty(), RoadClass.fromHighwayTag(""));
        assertEquals(Optional.empty(), RoadClass.fromHighwayTag(null));
    }

    /** Bigger roads are faster. */
    @Test
    void speedsFallWithTheSizeOfTheRoad() {
        RoadClass[] all = RoadClass.values();
        for (int i = 1; i < all.length; i++) {
            assertTrue(all[i - 1].speedKmh() > all[i].speedKmh(), all[i - 1] + " should be faster than " + all[i]);
        }
    }
}
