package com.medidispatch.sim;

import com.medidispatch.core.domain.Consciousness;
import com.medidispatch.core.domain.VitalsReading;
import org.junit.jupiter.api.Test;

import java.time.Instant;

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
}
