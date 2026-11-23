package com.medidispatch.core.engine;

import com.medidispatch.core.domain.Capability;
import com.medidispatch.core.domain.Facility;
import com.medidispatch.core.domain.Location;
import com.medidispatch.core.port.FacilityRepository;
import com.medidispatch.core.port.TravelTime;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
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
 * "Nearest" means reached soonest, with the siren on, by the {@link TravelTime} given (a straight line by default;
 * real roads with traffic in the service); a hospital that cannot be reached is skipped. Empty only if no hospital is
 * registered (or reachable). Equal times are decided by id, so the answer never depends on storage order. A
 * placeholder policy, not clinical guidance.
 */
// ponytail: it times every hospital (tens in a city, one route each); a spatial pre-filter if there are ever hundreds.
public class DestinationChooser {

    private final FacilityRepository facilities;
    private final TravelTime travel;

    /**
     * Chooses by straight-line travel time (that is, by distance).
     *
     * @param facilities the facility registry (hospitals and clinics)
     */
    public DestinationChooser(FacilityRepository facilities) {
        this(facilities, new StraightLineTravelTime());
    }

    /**
     * @param facilities the facility registry (hospitals and clinics)
     * @param travel     how long the ambulance takes to reach a hospital (siren on)
     * @throws IllegalArgumentException if either is missing
     */
    public DestinationChooser(FacilityRepository facilities, TravelTime travel) {
        if (facilities == null || travel == null) {
            throw new IllegalArgumentException("facilities and travel are required");
        }
        this.facilities = facilities;
        this.travel = travel;
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
     * @return the candidate reached soonest from {@code from} with the siren on (lower id on a tie), or empty if
     *         there is none; a hospital that cannot be reached by road is skipped
     */
    private Optional<Facility> nearest(List<Facility> candidates, Location from) {
        Facility best = null;
        double bestSeconds = Double.MAX_VALUE;
        for (Facility h : candidates) {
            OptionalDouble seconds = travel.seconds(from, h.location(), true);
            if (seconds.isEmpty()) {
                continue;
            }
            double s = seconds.getAsDouble();
            // On the first pass best is null, but s < MAX_VALUE is already true, so best.id() is never reached.
            if (s < bestSeconds || (s == bestSeconds && h.id().compareTo(best.id()) < 0)) {
                best = h;
                bestSeconds = s;
            }
        }
        return Optional.ofNullable(best);
    }
}
