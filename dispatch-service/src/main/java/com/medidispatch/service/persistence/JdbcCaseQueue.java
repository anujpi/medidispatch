package com.medidispatch.service.persistence;

import com.medidispatch.core.domain.Case;
import com.medidispatch.core.port.CaseQueue;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Optional;

/**
 * {@link CaseQueue} on Postgres: no separate store, just the {@code queued} flag on {@code cases} (this adapter is the
 * only one that writes it). A small partial index ({@code queued_cases ... WHERE queued}) keeps the few waiting rows
 * apart from the ever-growing history.
 *
 * <p>Most urgent first: CRITICAL, HIGH, MEDIUM, LOW, then the oldest, then by id (the same order as
 * InMemoryCaseQueue). One difference: the queue ranks by the band saved NOW, not the band the case had when it was
 * queued (in memory an entry kept a snapshot). The engine re-queues whenever a band changes, so it sees no difference.
 *
 * <p>{@link #pollNext()} finds the top case and takes it off the queue in ONE statement, so no second worker can take
 * the same case between "look" and "take". See there for {@code FOR UPDATE SKIP LOCKED}.
 */
// ponytail: the band order is spelled out in SQL (BAND_RANK) rather than taken from RiskBand. Adding a band needs a
// migration anyway (the CHECK constraint), so update it there.
public class JdbcCaseQueue implements CaseQueue {

    /** Most urgent first. */
    private static final String BAND_RANK =
            "CASE band WHEN 'CRITICAL' THEN 0 WHEN 'HIGH' THEN 1 WHEN 'MEDIUM' THEN 2 ELSE 3 END";

    /** The queued cases, most urgent first. */
    private static final String MOST_URGENT_FIRST = "WHERE queued ORDER BY " + BAND_RANK + ", opened_at, id";

    private final JdbcClient jdbc;

    /**
     * @param jdbc Spring's fluent JDBC API over the application's DataSource
     */
    public JdbcCaseQueue(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Puts the case in the queue; a case already queued stays queued once (and is ranked by its saved band).
     *
     * @param c the case; it must already be saved
     * @throws IllegalArgumentException if {@code c} is null
     * @throws IllegalStateException    if the case was never saved: the update would change nothing, and silently
     *                                  losing an emergency is worse than failing loudly
     */
    @Override
    public void enqueue(Case c) {
        if (c == null) {
            throw new IllegalArgumentException("case is required");
        }
        int changed = jdbc.sql("UPDATE cases SET queued = true WHERE id = :id")
                .param("id", c.id())
                .update();
        if (changed == 0) {
            throw new IllegalStateException("Case " + c.id() + " must be saved before it is queued");
        }
    }

    /**
     * Takes the most urgent case off the queue and returns it.
     *
     * <p>The inner SELECT picks the top case and LOCKS its row ({@code FOR UPDATE}); the outer UPDATE clears its flag;
     * {@code RETURNING} hands the row back, all in one statement. {@code SKIP LOCKED}: if another worker has locked
     * that row (it is taking it right now), do not wait, take the next one. Without it there is a classic trap: the
     * second worker waits, then re-checks the row, finds it no longer queued and, because of {@code LIMIT 1}, returns
     * NOTHING although other cases are waiting.
     *
     * @return the case, as saved, or empty if nothing is waiting
     */
    @Override
    public Optional<Case> pollNext() {
        return jdbc.sql("""
                UPDATE cases SET queued = false
                WHERE id = (SELECT id FROM cases %s LIMIT 1 FOR UPDATE SKIP LOCKED)
                RETURNING id, patient_id, status, band, opened_at
                """.formatted(MOST_URGENT_FIRST))
                .query((rs, rowNum) -> JdbcCaseRepository.toCase(rs))
                .optional();
    }

    /**
     * @param caseId the case
     * @return {@code true} if it was queued and is now out of the queue; {@code false} if it was not queued
     * @throws IllegalArgumentException if {@code caseId} is null
     */
    @Override
    public boolean remove(String caseId) {
        if (caseId == null) {
            throw new IllegalArgumentException("CaseId is required");
        }
        int changed = jdbc.sql("UPDATE cases SET queued = false WHERE id = :id AND queued")
                .param("id", caseId)
                .update();
        return changed == 1;
    }

    /**
     * @return how many cases are waiting
     */
    @Override
    public int size() {
        return jdbc.sql("SELECT count(*) FROM cases WHERE queued")
                .query(Integer.class)
                .single();
    }

    /**
     * @return the case {@link #pollNext()} would take, without taking it; empty if nothing is waiting
     */
    @Override
    public Optional<Case> peekNext() {
        return jdbc.sql("SELECT id, patient_id, status, band, opened_at FROM cases " + MOST_URGENT_FIRST + " LIMIT 1")
                .query((rs, rowNum) -> JdbcCaseRepository.toCase(rs))
                .optional();
    }
}
