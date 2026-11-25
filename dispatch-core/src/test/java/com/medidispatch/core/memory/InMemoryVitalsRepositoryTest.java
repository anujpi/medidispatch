package com.medidispatch.core.memory;

import java.util.List;
import com.medidispatch.core.domain.Consciousness;
import com.medidispatch.core.domain.VitalsReading;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

public class InMemoryVitalsRepositoryTest {

    private static final Instant T0 = Instant.parse("2026-10-07T12:00:00Z");

    private final InMemoryVitalsRepository repo = new InMemoryVitalsRepository();

    private VitalsReading reading(String patientId, Instant at) {
        return new VitalsReading(patientId, at, 75, 98, 120, 80, 14, 36.8, Consciousness.ALERT);
    }

    @Test
    void aPatientWithNoReadingsHasNoLatest() {
        assertTrue(repo.latestFor("p1").isEmpty());
    }

    @Test
    void theOnlyReadingIsTheLatest() {
        VitalsReading r = reading("p1", T0);
        repo.save(r);

        assertEquals(r, repo.latestFor("p1").get());
    }

    @Test
    void theLatestIsChosenByTimeNotBySaveOrder() {
        VitalsReading newer = reading("p1", T0.plusSeconds(60));
        VitalsReading older = reading("p1", T0);
        repo.save(newer);
        repo.save(older);                                     // arrives late

        assertEquals(newer, repo.latestFor("p1").get());
    }

    @Test
    void patientsAreKeptApart() {
        VitalsReading forP1 = reading("p1", T0);
        VitalsReading forP2 = reading("p2", T0.plusSeconds(60));
        repo.save(forP1);
        repo.save(forP2);

        assertEquals(forP1, repo.latestFor("p1").get());
        assertEquals(forP2, repo.latestFor("p2").get());
        assertTrue(repo.latestFor("p3").isEmpty());
    }

    @Test
    void nullArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> repo.save(null));
        assertThrows(IllegalArgumentException.class, () -> repo.latestFor(null));
    }

    // ---- history, for the trend lines ----

    @Test
    void theHistoryIsInTimeOrderAndStartsAtTheGivenTime() {
        VitalsReading early = reading("p1", T0.minusSeconds(3600));
        VitalsReading a = reading("p1", T0);
        VitalsReading b = reading("p1", T0.plusSeconds(30));
        VitalsReading c = reading("p1", T0.plusSeconds(60));
        repo.save(c);
        repo.save(early);
        repo.save(a);                                   // saved out of order: a late reading
        repo.save(b);
        repo.save(reading("p2", T0));                   // another patient's

        assertEquals(List.of(a, b, c), repo.historyFor("p1", T0));
        assertEquals(List.of(), repo.historyFor("p2", T0.plusSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> repo.historyFor(null, T0));
        assertThrows(IllegalArgumentException.class, () -> repo.historyFor("p1", null));
    }

    @Test
    void onlyTheNewestReadingsAreKeptPerPatient() {
        for (int i = 0; i < InMemoryVitalsRepository.KEEP + 5; i++) {
            repo.save(reading("p1", T0.plusSeconds(i)));
        }

        List<VitalsReading> kept = repo.historyFor("p1", T0);
        assertEquals(InMemoryVitalsRepository.KEEP, kept.size());
        assertEquals(T0.plusSeconds(5), kept.get(0).timeStamp());
        assertEquals(T0.plusSeconds(InMemoryVitalsRepository.KEEP + 4), repo.latestFor("p1").get().timeStamp());
    }
}
