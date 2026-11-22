package com.medidispatch.service;

import java.time.Instant;
import java.util.List;

/**
 * Everything a live screen shows, frozen at one moment: the whole fleet, the open cases, the trips under way and the
 * hospitals and clinics (with their diversion status).
 *
 * <p>Built inside the {@link DispatchService} lock, then handed to the live feed's thread, which reads it with no
 * lock at all. That is only safe because nothing can change it after it is built: the records inside are
 * immutable, and the lists are unmodifiable copies.
 *
 * @param at          when it was taken (the engine's clock)
 * @param units       every unit, sorted by id
 * @param openCases   every case that is not closed or cancelled, oldest first
 * @param activeTrips every trip that has not ended, in no particular order (screens look trips up by id)
 * @param facilities  every hospital and clinic, sorted by id (tens of rows: cheap to send every second, and a
 *                    diversion change shows up live)
 */
public record LiveSnapshot(Instant at, List<UnitInfo> units, List<CaseInfo> openCases, List<TripInfo> activeTrips,
                           List<FacilityInfo> facilities) {

    /**
     * Takes unmodifiable copies of the lists. A record field holding a normal list could still be changed through
     * {@code add()} by anyone who kept a reference to that list.
     */
    public LiveSnapshot {
        units = List.copyOf(units);
        openCases = List.copyOf(openCases);
        activeTrips = List.copyOf(activeTrips);
        facilities = List.copyOf(facilities);
    }
}
