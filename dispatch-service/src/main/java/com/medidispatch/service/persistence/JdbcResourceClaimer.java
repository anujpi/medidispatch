package com.medidispatch.service.persistence;

import com.medidispatch.core.port.ResourceClaimer;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Optional;

/**
 * {@link ResourceClaimer} on Postgres: the claim is the {@code claimed_by} column of {@code units}.
 *
 * <p>Each call is ONE conditional UPDATE, and the number of rows it changed is the answer. Postgres locks the row
 * while updating it, so when two claimers race (two threads, or two copies of the service), the second waits for the
 * first to commit, re-checks the WHERE, finds the unit taken and changes 0 rows. No Java lock is needed.
 *
 * <p>One difference from the in-memory claimer: a unit that does not exist cannot be claimed (no row changes, so
 * {@code false}); in memory any id could be claimed. The engine only claims units it has just found, so nothing
 * depends on it. {@code claimed_by} references {@code cases}, so the case must already be saved.
 */
public class JdbcResourceClaimer implements ResourceClaimer {

    private final JdbcClient jdbc;

    /**
     * @param jdbc Spring's fluent JDBC API over the application's DataSource
     */
    public JdbcResourceClaimer(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Claims the unit for the case, if nobody else holds it. Claiming again for the same case succeeds.
     *
     * @param resourceId the unit
     * @param caseId     the case that wants it
     * @return {@code true} if the case now holds the unit
     * @throws IllegalArgumentException if an id is null or blank
     */
    @Override
    public boolean tryClaim(String resourceId, String caseId) {
        requireId(resourceId, "resourceId");
        requireId(caseId, "caseId");
        int changed = jdbc.sql("""
                UPDATE units SET claimed_by = :caseId
                WHERE id = :resourceId AND (claimed_by IS NULL OR claimed_by = :caseId)
                """)
                .param("caseId", caseId)
                .param("resourceId", resourceId)
                .update();
        return changed == 1;
    }

    /**
     * Lets the unit go, but only if this case holds it.
     *
     * @param resourceId the unit
     * @param caseId     the case that should be holding it
     * @return {@code true} if the claim was released; {@code false} if this case did not hold it
     * @throws IllegalArgumentException if an id is null or blank
     */
    @Override
    public boolean release(String resourceId, String caseId) {
        requireId(resourceId, "resourceId");
        requireId(caseId, "caseId");
        int changed = jdbc.sql("UPDATE units SET claimed_by = NULL WHERE id = :resourceId AND claimed_by = :caseId")
                .param("resourceId", resourceId)
                .param("caseId", caseId)
                .update();
        return changed == 1;
    }

    /**
     * @param resourceId the unit
     * @return the case holding it, or empty if it is free (or does not exist)
     * @throws IllegalArgumentException if the id is null or blank
     */
    @Override
    public Optional<String> claimedBy(String resourceId) {
        requireId(resourceId, "resourceId");
        // "IS NOT NULL" makes a free unit return no row at all (empty), instead of one row holding NULL.
        return jdbc.sql("SELECT claimed_by FROM units WHERE id = :resourceId AND claimed_by IS NOT NULL")
                .param("resourceId", resourceId)
                .query(String.class)
                .optional();
    }

    private static void requireId(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
