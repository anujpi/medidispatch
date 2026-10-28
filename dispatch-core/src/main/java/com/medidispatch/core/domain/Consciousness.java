package com.medidispatch.core.domain;

/**
 * Level of consciousness on the ACVPU scale, recorded with every {@link VitalsReading}.
 * <p>
 * Ordered from fully awake ({@link #ALERT}) to no response at all ({@link #UNRESPONSIVE}).
 * Anything other than {@code ALERT} is a warning sign in clinical scores such as NEWS2.
 */
public enum Consciousness {
    ALERT,CONFUSED,VOICE,PAIN,UNRESPONSIVE
}
