package com.medidispatch.core.domain;

/**
 * A specialist service a hospital offers, beyond its emergency department (which every registered hospital has, so
 * it is not listed here).
 * <p>
 * A crew starting a transport says which of these the patient needs (for example STROKE for a suspected stroke);
 * the destination is then chosen among hospitals that offer all of them. A placeholder list, not clinical guidance.
 */
public enum Capability {
    /** Heart attacks: a cardiac catheterisation lab (cath lab). */
    CARDIAC,
    /** Strokes: a stroke unit able to give clot-busting treatment quickly. */
    STROKE,
    /** Serious injuries: a trauma centre. */
    TRAUMA,
    /** Children: a paediatric emergency service. */
    PAEDIATRIC
}
