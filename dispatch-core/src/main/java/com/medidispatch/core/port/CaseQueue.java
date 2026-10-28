package com.medidispatch.core.port;

import com.medidispatch.core.domain.Case;

import java.util.Optional;

/**
 * Port: the queue of cases waiting for a unit, most urgent first.
 * <p>
 * Order: higher {@link com.medidispatch.core.domain.RiskBand} first, then the older case
 * ({@code openedAt}), then the case id so the order is always the same. Each case appears at most once.
 * <p>
 * The in-memory adapter is used by tests and the simulator; a Redis sorted set will implement it later.
 */
public interface CaseQueue {

    /**
     * Adds a case, or replaces it if it is already queued (for example after its band changed).
     *
     * @param c the case to queue
     * @throws IllegalArgumentException if {@code c} is missing
     */
    void enqueue(Case c);

    /**
     * Removes and returns the most urgent case.
     *
     * @return the most urgent case, or empty if the queue is empty
     */
    Optional<Case> pollNext();

    /**
     * Takes a case out of the queue, wherever it is.
     *
     * @param caseId the case to remove
     * @return {@code true} if it was queued and has been removed, {@code false} if it was not queued
     * @throws IllegalArgumentException if {@code caseId} is missing
     */
    boolean remove(String caseId);

    /**
     * Counts the waiting cases.
     *
     * @return how many cases are waiting
     */
    int size();

    /**
     * Returns the most urgent case without removing it, using the same order as {@link #pollNext()}.
     *
     * @return the most urgent case, or empty if the queue is empty
     */
    Optional<Case> peekNext();
}
