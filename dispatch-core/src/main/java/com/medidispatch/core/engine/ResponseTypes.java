package com.medidispatch.core.engine;

import com.medidispatch.core.domain.ResourceType;
import com.medidispatch.core.domain.RiskBand;

import java.util.Set;
//No more in use
final class ResponseTypes {
    private ResponseTypes() {}
    static Set<ResourceType> forBand(RiskBand Band){
        return switch(Band){
            case CRITICAL -> Set.of(ResourceType.AMBULANCE_ALS);
            case HIGH -> Set.of(ResourceType.AMBULANCE_ALS,ResourceType.AMBULANCE_BLS);
            case MEDIUM , LOW-> Set.of(ResourceType.NURSE);
        };
    }
}
