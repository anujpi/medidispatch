package com.medidispatch.routing;

import com.medidispatch.core.domain.Location;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DriveTest {

    private static final Location A = new Location(12.97, 77.59);
    private static final Location B = new Location(12.98, 77.59);

    @Test
    void thePointsAreCopied() {
        List<Location> points = new ArrayList<>(List.of(A, B));
        Drive drive = new Drive(points, 60, 1100);

        points.clear();

        assertEquals(List.of(A, B), drive.points());
        assertThrows(UnsupportedOperationException.class, () -> drive.points().add(A));
    }

    @Test
    void badDrivesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new Drive(null, 60, 1100));
        assertThrows(IllegalArgumentException.class, () -> new Drive(List.of(A), 60, 1100));
        assertThrows(IllegalArgumentException.class, () -> new Drive(List.of(A, B), -1, 1100));
        assertThrows(IllegalArgumentException.class, () -> new Drive(List.of(A, B), 60, -1));
    }
}
