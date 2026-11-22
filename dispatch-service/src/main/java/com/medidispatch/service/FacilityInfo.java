package com.medidispatch.service;

import com.medidispatch.core.domain.Capability;
import com.medidispatch.core.domain.Facility;
import com.medidispatch.core.domain.FacilityKind;

import java.util.Set;

/**
 * A read-only snapshot of a hospital or clinic. The position is flattened into two numbers so the JSON stays simple.
 *
 * @param id           the facility id
 * @param name         the name people know it by
 * @param kind         HOSPITAL or CLINIC
 * @param latitude     where it is
 * @param longitude    where it is
 * @param capabilities the specialist services it offers (always empty for a clinic)
 * @param accepting    whether its emergency department takes new patients ({@code false} = on diversion; always
 *                     {@code false} for a clinic)
 */
public record FacilityInfo(String id, String name, FacilityKind kind, double latitude, double longitude,
                           Set<Capability> capabilities, boolean accepting) {

    /**
     * @param f the engine's facility
     * @return the snapshot
     */
    static FacilityInfo from(Facility f) {
        return new FacilityInfo(f.id(), f.name(), f.kind(), f.location().latitude(), f.location().longitude(),
                f.capabilities(), f.accepting());
    }
}
