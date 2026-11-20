package com.medidispatch.service;

import java.time.Instant;
import java.util.List;

/**
 * Everything a live screen shows, frozen at one moment: the whole fleet, the open cases and the trips under way.
 *
 * <p>Built inside the {@link DispatchService} lock, then handed to the live feed's thread, which reads it with no
 * lock at all. That is only safe because nothing can change it after it is built: the records inside are
 * immutable, and the lists are unmodifiable copies.
 *
 * @param at          when it was taken (the engine's clock)
 * @param units       every unit, sorted by id
 * @param openCases   every case that is not closed or cancelled, oldest first
 * @param activeTrips every trip that has not ended, in no particular order (screens look trips up by id)
 */
public record LiveSnapshot(Instant at, List<UnitInfo> units, List<CaseInfo> openCases, List<TripInfo> activeTrips) {

    /**
     * Takes unmodifiable copies of the lists. A record field holding a normal list could still be changed through
     * {@code add()} by anyone who kept a reference to that list.
     */
    public LiveSnapshot {
        units = List.copyOf(units);
        openCases = List.copyOf(openCases);
        activeTrips = List.copyOf(activeTrips);
    }
}
