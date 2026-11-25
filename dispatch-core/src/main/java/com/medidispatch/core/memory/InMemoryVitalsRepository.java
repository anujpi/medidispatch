package com.medidispatch.core.memory;

import com.medidispatch.core.domain.VitalsReading;
import com.medidispatch.core.port.VitalsRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory {@link VitalsRepository} for tests and the simulator. Not thread-safe.
 * <p>
 * Keeps each patient's readings in time order (a late reading is put in its place), up to {@link #KEEP} per
 * patient, so an endless simulation cannot fill the memory.
 */
// ponytail: a capped list per patient and an insertion scan; fine for tests and the simulator. The database adapter
// keeps everything and answers from its (patient, time) index.
public class InMemoryVitalsRepository implements VitalsRepository {

    /** The most readings kept per patient; the oldest go first. About 8 hours at one reading every 30 s. */
    public static final int KEEP = 1000;

    private final Map<String, List<VitalsReading>> byPatient = new HashMap<>();

    @Override
    public void save(VitalsReading reading) {
        if (reading == null) {
            throw new IllegalArgumentException("reading is required");
        }
        List<VitalsReading> list = byPatient.computeIfAbsent(reading.patientId(), (id) -> new ArrayList<>());
        int at = list.size();
        while (at > 0 && list.get(at - 1).timeStamp().isAfter(reading.timeStamp())) {
            at--;                                      // a late reading goes before the newer ones
        }
        list.add(at, reading);
        if (list.size() > KEEP) {
            list.remove(0);
        }
    }

    @Override
    public Optional<VitalsReading> latestFor(String patientId) {
        if (patientId == null) {
            throw new IllegalArgumentException("patientId is required");
        }
        List<VitalsReading> list = byPatient.get(patientId);
        return list == null || list.isEmpty() ? Optional.empty() : Optional.of(list.get(list.size() - 1));
    }

    @Override
    public List<VitalsReading> historyFor(String patientId, Instant from) {
        if (patientId == null || from == null) {
            throw new IllegalArgumentException("patientId and from are required");
        }
        List<VitalsReading> found = new ArrayList<>();
        for (VitalsReading r : byPatient.getOrDefault(patientId, List.of())) {
            if (!r.timeStamp().isBefore(from)) {
                found.add(r);
            }
        }
        return found;
    }
}
