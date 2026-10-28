package com.medidispatch.core.response;

import com.medidispatch.core.domain.Case;
import com.medidispatch.core.domain.Patient;
import com.medidispatch.core.domain.ResourceType;

import java.util.List;

/**
 * The escalation ladder: send the least capable unit that is enough, so advanced (ALS) ambulances stay
 * free for the worst cases. A <strong>placeholder</strong> table, <strong>not clinical guidance</strong>.
 * <ul>
 *   <li>CRITICAL: ALS only (no fallback; preemption can free an ALS instead).</li>
 *   <li>HIGH: BLS first, ALS only if no BLS can be had.</li>
 *   <li>MEDIUM: a nurse.</li>
 *   <li>LOW: nobody (self-care).</li>
 * </ul>
 * Uses only the band for now; the patient argument is where context will come in.
 */
public class LadderResponseDecider implements ResponseDecider{

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if the case or patient is missing
     */
    @Override
    public ResponsePlan decide(Case c, Patient patient) {
        if(c == null || patient == null){
            throw new IllegalArgumentException("Case and Patient are required");
        }
        return switch (c.band()){
            case CRITICAL -> new ResponsePlan(List.of(ResourceType.AMBULANCE_ALS));
            case HIGH -> new ResponsePlan(List.of(ResourceType.AMBULANCE_BLS, ResourceType.AMBULANCE_ALS));
            case MEDIUM -> new ResponsePlan(List.of(ResourceType.NURSE));
            case LOW -> new ResponsePlan(List.of());
        };
    }
}
