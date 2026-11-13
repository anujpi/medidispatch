package com.medidispatch.sim.fx;

import com.medidispatch.core.domain.RiskBand;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class ThemeTest {

    @Test
    void theToggleGoesBackAndForth() {
        assertEquals(Theme.DARK, Theme.LIGHT.other());
        assertEquals(Theme.LIGHT, Theme.DARK.other());
    }

    @Test
    void theThemesReallyLookDifferent() {
        assertNotEquals(Theme.LIGHT.background(), Theme.DARK.background());
        assertNotEquals(Theme.LIGHT.text(), Theme.DARK.text());
        assertEquals("light", Theme.LIGHT.cssClass());
        assertEquals("dark", Theme.DARK.cssClass());
    }

    @Test
    void everyBandHasItsOwnColourAndItIsTheSameInBothThemes() {
        Set<Object> colours = new HashSet<>();
        for (RiskBand band : RiskBand.values()) {
            colours.add(Theme.DARK.band(band));
            assertEquals(Theme.LIGHT.band(band), Theme.DARK.band(band));
        }
        assertEquals(RiskBand.values().length, colours.size());
    }
}
