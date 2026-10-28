package com.medidispatch.core.domain;

/**
 * How urgent a patient's condition is, from least to most urgent.
 * <p>
 * The declaration order matters: the engine compares bands with {@code compareTo}, so
 * {@code LOW < MEDIUM < HIGH < CRITICAL}. Never reorder the constants.
 * A case is opened (and help is sent) from {@link #MEDIUM} upwards.
 */
public enum RiskBand {
    LOW,MEDIUM,HIGH,CRITICAL
}

