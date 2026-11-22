package com.medidispatch.service.persistence;

import com.medidispatch.core.domain.Case;
import com.medidispatch.core.domain.CaseStatus;
import com.medidispatch.core.domain.RiskBand;
import com.medidispatch.core.port.CaseRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * {@link CaseRepository} on Postgres: table {@code cases}.
 *
 * <p>Column ownership: this adapter never writes {@code queued}; that column belongs to {@link JdbcCaseQueue}.
 * CaseManager enqueues a case BEFORE it saves it, so a save that wrote {@code queued} would undo the enqueue.
 *
 * <p>"Open" means not CLOSED or CANCELLED ({@link CaseStatus#isTerminal()}). The open-case queries spell those two
 * values out in the SQL, word for word like the partial index {@code one_open_case_per_patient}: Postgres only uses
 * a partial index when it can prove the query's condition matches the index's, which it cannot always do for
 * {@code ?} parameters once the driver reuses a prepared plan. The database also refuses a second open case for one
 * patient (that index is unique): {@code save} then throws {@link org.springframework.dao.DuplicateKeyException}.
 */
public class JdbcCaseRepository implements CaseRepository {

    private static final String COLUMNS = "id, patient_id, status, band, opened_at";

    private final JdbcClient jdbc;

    /**
     * @param jdbc Spring's fluent JDBC API over the application's DataSource
     */
    public JdbcCaseRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Stores the case, or updates the stored one with the same id (upsert). Every column is written except
     * {@code queued}.
     *
     * @param c the case
     * @throws IllegalArgumentException if {@code c} is null
     */
    @Override
    public void save(Case c) {
        if (c == null) {
            throw new IllegalArgumentException("case is required");
        }
        jdbc.sql("""
                INSERT INTO cases (id, patient_id, status, band, opened_at)
                VALUES (:id, :patientId, :status, :band, :openedAt)
                ON CONFLICT (id) DO UPDATE SET
                    patient_id = excluded.patient_id, status = excluded.status, band = excluded.band,
                    opened_at = excluded.opened_at
                """)
                .param("id", c.id())
                .param("patientId", c.patientId())
                .param("status", c.status().name())
                .param("band", c.band().name())
                .param("openedAt", DbTime.toDb(c.openedAt()))
                .update();
    }

    /**
     * @param id the case id
     * @return the case, or empty if there is none with that id
     * @throws IllegalArgumentException if {@code id} is null
     */
    @Override
    public Optional<Case> findById(String id) {
        if (id == null) {
            throw new IllegalArgumentException("id is required");
        }
        return jdbc.sql("SELECT " + COLUMNS + " FROM cases WHERE id = :id")
                .param("id", id)
                .query((rs, rowNum) -> toCase(rs))
                .optional();
    }

    /**
     * @param patientId the patient
     * @return the patient's open case, or empty (at most one exists: the database guarantees it)
     * @throws IllegalArgumentException if {@code patientId} is null
     */
    @Override
    public Optional<Case> findOpenByPatientId(String patientId) {
        if (patientId == null) {
            throw new IllegalArgumentException("PatientId is required");
        }
        return jdbc.sql("SELECT " + COLUMNS + " FROM cases WHERE patient_id = :patientId AND status NOT IN ('CLOSED', 'CANCELLED')")
                .param("patientId", patientId)
                .query((rs, rowNum) -> toCase(rs))
                .optional();
    }

    /**
     * @return every open case, oldest first; the id decides ties, so the order never changes
     */
    @Override
    public List<Case> findOpen() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM cases WHERE status NOT IN ('CLOSED', 'CANCELLED') ORDER BY opened_at, id")
                .query((rs, rowNum) -> toCase(rs))
                .list();
    }

    /**
     * Turns the current row (selected with {@link #COLUMNS}) back into a case. Package-private: {@link JdbcCaseQueue}
     * reads the same columns.
     */
    static Case toCase(ResultSet rs) throws SQLException {
        return Case.restore(
                rs.getString("id"),
                rs.getString("patient_id"),
                CaseStatus.valueOf(rs.getString("status")),
                RiskBand.valueOf(rs.getString("band")),
                DbTime.fromDb(rs, "opened_at"));
    }
}
