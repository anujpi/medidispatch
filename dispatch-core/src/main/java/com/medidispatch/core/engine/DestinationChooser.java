package com.medidispatch.core.engine;

import com.medidispatch.core.domain.Capability;
import com.medidispatch.core.domain.Facility;
import com.medidispatch.core.domain.Location;
import com.medidispatch.core.port.FacilityRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Picks the hospital a patient is taken to. Clinics are never chosen: they have no emergency department.
 * <p>
 * The rules, tried in order (the first that finds a hospital wins):
 * <ol>
 *   <li>the nearest hospital that offers everything needed AND is accepting patients;</li>
 *   <li>otherwise the nearest one that offers everything needed, even on diversion: diversion is about general
 *       load, and a stroke patient still needs a stroke unit;</li>
 *   <li>otherwise (no hospital has what is needed) the nearest accepting one: every hospital has an emergency
 *       department;</li>
 *   <li>otherwise the nearest hospital of any kind.</li>
 * </ol>
 * Empty only if no hospital is registered at all. Equal distances are decided by id, so the answer never depends on
 * storage order. A placeholder policy, not clinical guidance.
 */
// ponytail: straight-line distances (Location.distanceKmTo), not road distances; a routing service later. And it
// reads every facility, fine for the tens in a city; a spatial query if there are ever thousands.
public class DestinationChooser {

    private final FacilityRepository facilities;

    /**
     * @param facilities the facility registry (hospitals and clinics)
     */
    public DestinationChooser(FacilityRepository facilities) {
        this.facilities = facilities;
    }

    /**
     * @param from   where the patient is now (the unit's position when transport starts)
     * @param needed the specialist services the patient needs; empty means any emergency department
     * @return the hospital to go to, or empty if no hospital is registered
     * @throws IllegalArgumentException if an argument is missing
     */
    public Optional<Facility> choose(Location from, Set<Capability> needed) {
        if (from == null) {
            throw new IllegalArgumentException("from is required");
        }
        if (needed == null) {
            throw new IllegalArgumentException("needed is required (empty means any emergency department)");
        }
        // Only hospitals from here on, so no rule (not even "nearest at all") can fall back to a clinic.
        List<Facility> all = new ArrayList<>();
        for (Facility f : facilities.findAll()) {
            if (f.isHospital()) {
                all.add(f);
            }
        }
        List<Facility> capable = new ArrayList<>();
        for (Facility h : all) {
            if (h.canTreat(needed)) {
                capable.add(h);
            }
        }

        // Rule 1: capable and accepting.
        Optional<Facility> best = nearest(accepting(capable), from);
        if (best.isPresent()) {
            return best;
        }
        // Rule 2: capable, even on diversion.
        best = nearest(capable, from);
        if (best.isPresent()) {
            return best;
        }
        // Rule 3: nobody has what is needed: the nearest open emergency department.
        best = nearest(accepting(all), from);
        if (best.isPresent()) {
            return best;
        }
        // Rule 4: everything is on diversion: the nearest hospital at all.
        return nearest(all, from);
    }

    /** @return only the hospitals that are accepting patients */
    private static List<Facility> accepting(List<Facility> candidates) {
        List<Facility> open = new ArrayList<>();
        for (Facility h : candidates) {
            if (h.accepting()) {
                open.add(h);
            }
        }
        return open;
    }

    /**
     * @return the candidate closest to {@code from} (lower id on a tie), or empty if there are no candidates
     */
    private static Optional<Facility> nearest(List<Facility> candidates, Location from) {
        Facility best = null;
        double bestKm = Double.MAX_VALUE;
        for (Facility h : candidates) {
            double km = from.distanceKmTo(h.location());
            // On the first pass best is null, but km < MAX_VALUE is already true, so best.id() is never reached.
            if (km < bestKm || (km == bestKm && h.id().compareTo(best.id()) < 0)) {
                best = h;
                bestKm = km;
            }
        }
        return Optional.ofNullable(best);
    }
}
