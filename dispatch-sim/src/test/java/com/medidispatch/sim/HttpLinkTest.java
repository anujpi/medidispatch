package com.medidispatch.sim;

import com.medidispatch.core.domain.Capability;
import com.medidispatch.core.domain.Consciousness;
import com.medidispatch.core.domain.Location;
import com.medidispatch.core.domain.VitalsReading;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The two-clocks rule: what goes to the service carries real time, not the city's sped-up time. */
public class HttpLinkTest {

    @Test
    void aReadingIsSentWithTheRealTimeAndUnchangedValues() {
        VitalsReading inTheCity = new VitalsReading("p001", Instant.parse("2026-01-05T09:30:00Z"), 150, 80, 80, 50,
                35, 39.5, Consciousness.UNRESPONSIVE);
        Instant realNow = Instant.parse("2026-11-22T15:04:05.123456Z");

        VitalsReading sent = HttpLink.retimed(inTheCity, realNow);

        assertEquals(realNow, sent.timeStamp());
        assertEquals("p001", sent.patientId());
        assertEquals(150, sent.heartRate());
        assertEquals(80, sent.spo2());
        assertEquals(80, sent.systolicBp());
        assertEquals(50, sent.diastolicBp());
        assertEquals(35, sent.respRate());
        assertEquals(39.5, sent.temperature());
        assertEquals(Consciousness.UNRESPONSIVE, sent.consciousness());
    }

    // ---- what the crew says a patient needs (S2) ----

    /** A child always needs paediatrics, whatever the dice say. */
    @Test
    void aChildAlwaysNeedsPaediatrics() {
        Random dice = new Random(7);
        for (int i = 0; i < 200; i++) {
            assertTrue(HttpLink.needsFor(9, dice).contains(Capability.PAEDIATRIC));
        }
    }

    /** An adult never gets paediatrics, and gets at most one special need. */
    @Test
    void anAdultNeedsAtMostOneThingAndNeverPaediatrics() {
        Random dice = new Random(7);
        for (int i = 0; i < 200; i++) {
            Set<Capability> needs = HttpLink.needsFor(68, dice);
            assertFalse(needs.contains(Capability.PAEDIATRIC));
            assertTrue(needs.size() <= 1);
        }
    }

    /** Over many adults every outcome happens, so patients spread over the city's hospitals. */
    @Test
    void adultsGetEveryKindOfNeed() {
        Random dice = new Random(7);
        Set<Set<Capability>> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            seen.add(HttpLink.needsFor(68, dice));
        }
        assertEquals(Set.of(Set.of(), Set.of(Capability.CARDIAC), Set.of(Capability.STROKE), Set.of(Capability.TRAUMA)),
                seen);
    }

    @Test
    void theSameSeedGivesTheSameNeeds() {
        Random a = new Random(11);
        Random b = new Random(11);
        for (int i = 0; i < 50; i++) {
            assertEquals(HttpLink.needsFor(40 + i, a), HttpLink.needsFor(40 + i, b));
        }
    }

    /** The answer of GET /api/route becomes the points to drive and the time. */
    @Test
    void aRouteAnswerIsReadIntoAWay() {
        JsonNode answer = JsonMapper.builder().build().readTree(
                "{\"seconds\": 504.5, \"metres\": 6500, \"points\": [[12.93, 77.62], [12.931, 77.615], [12.925, 77.59]]}");

        CityLink.Way way = HttpLink.way(answer);

        assertEquals(List.of(new Location(12.93, 77.62), new Location(12.931, 77.615), new Location(12.925, 77.59)),
                way.points());
        assertEquals(504.5, way.seconds());
    }
}
