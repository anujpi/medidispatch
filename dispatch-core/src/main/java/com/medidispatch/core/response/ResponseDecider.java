package com.medidispatch.core.response;

import com.medidispatch.core.domain.Case;
import com.medidispatch.core.domain.Patient;

/**
 * Strategy: decides what response a case needs, as a {@link ResponsePlan}.
 * <p>
 * The {@code Dispatcher} and {@code Preemptor} only know this interface, so the decision logic can grow
 * (patient context, first responders) or be swapped in the simulator without touching them.
 */
public interface ResponseDecider {

    /**
     * Decides the response for a case.
     *
     * @param c       the case, which carries the current band
     * @param patient the patient, for decisions that use context (conditions, history)
     * @return the plan: which unit types to try, in order of preference
     */
    ResponsePlan decide(Case c, Patient patient);
}
