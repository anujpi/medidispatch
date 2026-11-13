package com.medidispatch.sim.fx;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ViewportTest {

    private static final double DELTA = 1e-9;

    private final Viewport viewport = new Viewport();

    @Test
    void atTheStartNothingIsMovedOrZoomed() {
        assertEquals(1.0, viewport.zoom(), DELTA);
        assertEquals(120, viewport.toScreenX(120), DELTA);
        assertEquals(80, viewport.toScreenY(80), DELTA);
    }

    @Test
    void zoomingKeepsThePointUnderTheMouseStill() {
        double baseX = viewport.toBaseX(300);
        double baseY = viewport.toBaseY(200);

        viewport.zoomAt(300, 200, 2.0);

        assertEquals(2.0, viewport.zoom(), DELTA);
        assertEquals(300, viewport.toScreenX(baseX), DELTA);         // the same map point is still under the mouse
        assertEquals(200, viewport.toScreenY(baseY), DELTA);
    }

    @Test
    void zoomingDoublesDistancesOnScreen() {
        viewport.zoomAt(0, 0, 2.0);

        assertEquals(200, viewport.toScreenX(100) - viewport.toScreenX(0), DELTA);
    }

    @Test
    void zoomStaysBetweenTheLimits() {
        viewport.zoomAt(0, 0, 100);
        assertEquals(Viewport.MAX_ZOOM, viewport.zoom(), DELTA);

        viewport.zoomAt(0, 0, 0.001);
        assertEquals(Viewport.MIN_ZOOM, viewport.zoom(), DELTA);
    }

    @Test
    void draggingMovesTheMap() {
        viewport.panBy(30, -20);

        assertEquals(130, viewport.toScreenX(100), DELTA);
        assertEquals(80, viewport.toScreenY(100), DELTA);
    }

    @Test
    void screenAndBaseConvertBackAndForth() {
        viewport.zoomAt(250, 150, 3.0);
        viewport.panBy(-40, 25);

        assertEquals(123.0, viewport.toBaseX(viewport.toScreenX(123.0)), DELTA);
        assertEquals(456.0, viewport.toBaseY(viewport.toScreenY(456.0)), DELTA);
    }

    @Test
    void resetShowsTheWholeCityAgain() {
        viewport.zoomAt(250, 150, 3.0);
        viewport.panBy(-40, 25);

        viewport.reset();

        assertEquals(1.0, viewport.zoom(), DELTA);
        assertEquals(50, viewport.toScreenX(50), DELTA);
        assertEquals(50, viewport.toScreenY(50), DELTA);
    }

    @Test
    void aZoomFactorMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> viewport.zoomAt(0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> viewport.zoomAt(0, 0, -2));
    }
}
