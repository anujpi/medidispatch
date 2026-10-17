package com.medidispatch.core.memory;

import com.medidispatch.core.port.ResourceClaimer;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryResourceClaimer implements ResourceClaimer {
    private final Map<String,String> claims = new ConcurrentHashMap<>();
    @Override
    public boolean tryClaim(String resourceId, String caseId) {
        requireId(resourceId,"resourceId");
        requireId(caseId,"caseId");
        String holder = claims.putIfAbsent(resourceId,caseId); // one atomic step
        return holder == null || holder.equals(caseId);

    }
    @Override
    public boolean release(String resourceId, String caseId) {
        requireId(resourceId,"resourceId");
        requireId(caseId,"caseId");
        return claims.remove(resourceId,caseId);
    }
    @Override
    public Optional<String> claimedBy(String resourceId) {
        requireId(resourceId, "resourceId");
        return Optional.ofNullable(claims.get(resourceId));
    }
    private static void requireId(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

}
