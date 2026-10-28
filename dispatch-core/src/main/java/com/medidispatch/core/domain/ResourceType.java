package com.medidispatch.core.domain;

/**
 * Kinds of unit the engine can send.
 * <ul>
 *   <li>{@link #AMBULANCE_ALS}: advanced life support (paramedics, drugs, defibrillator); the scarcest unit.</li>
 *   <li>{@link #AMBULANCE_BLS}: basic life support.</li>
 *   <li>{@link #NURSE}: a visiting nurse; also sent as a first responder.</li>
 *   <li>{@link #COURIER}: medicine delivery.</li>
 *   <li>{@link #DOCTOR}: a doctor on call, sent as a first responder to start care before the ambulance.</li>
 * </ul>
 */
public enum ResourceType {
    AMBULANCE_ALS,AMBULANCE_BLS,NURSE,COURIER,DOCTOR
}
