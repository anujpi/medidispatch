package com.medidispatch.core.port;

import java.util.Optional;

public interface ResourceClaimer {
    boolean tryClaim(String resourceId,String caseId);
    boolean release(String resourceId, String caseId);
    Optional<String> claimedBy(String resourceId);
}
