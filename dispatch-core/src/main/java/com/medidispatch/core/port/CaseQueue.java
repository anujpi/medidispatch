package com.medidispatch.core.port;

import com.medidispatch.core.domain.Case;

import java.util.Optional;

public interface CaseQueue {
    void enqueue(Case c);
    Optional<Case> pollNext();
    boolean remove(String caseId);
    int size();
}
