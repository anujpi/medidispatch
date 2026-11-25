package com.medidispatch.service.persistence;

import com.medidispatch.core.domain.Consciousness;
import com.medidispatch.core.domain.VitalsReading;
import com.medidispatch.core.port.VitalsRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * {@link VitalsRepository} on Postgres: the {@code vitals} hypertable (TimescaleDB, one chunk per day), keyed by
 * (patient, time).
 *
 * <p>Every reading is kept (the history is the future training set), unlike the in-memory version, which only kept
 * the latest. The answers are the same: {@link #latestFor} is the reading with the latest time, whatever order they
 * were saved in. The patient must exist ({@code vitals.patient_id} references {@code patients}).
 */
// ponytail: one INSERT per reading. The design load (~830 readings/s) wants batched inserts; add them when a load test
// shows the need.
public class JdbcVitalsRepository implements VitalsRepository {

    private final JdbcClient jdbc;

    /**
     * @param jdbc Spring's fluent JDBC API over the application's DataSource
     */
    public JdbcVitalsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Stores the reading. A second reading for the same patient and time is ignored ({@code ON CONFLICT DO NOTHING}),
     * like in memory; VitalsProcessor rejects such duplicates before saving anyway.
     *
     * @param reading the reading
     * @throws IllegalArgumentException if {@code reading} is null
     */
    @Override
    public void save(VitalsReading reading) {
        if (reading == null) {
            throw new IllegalArgumentException("reading is required");
        }
        jdbc.sql("""
                INSERT INTO vitals (patient_id, at, heart_rate, spo2, systolic_bp, diastolic_bp, resp_rate,
                                    temperature, consciousness)
                VALUES (:patientId, :at, :heartRate, :spo2, :systolic, :diastolic, :respRate, :temperature,
                        :consciousness)
                ON CONFLICT (patient_id, at) DO NOTHING
                """)
                .param("patientId", reading.patientId())
                .param("at", DbTime.toDb(reading.timeStamp()))
                // measurements may be missing; the type is named because a null alone does not tell the driver
                .param("heartRate", reading.heartRate(), Types.INTEGER)
                .param("spo2", reading.spo2(), Types.INTEGER)
                .param("systolic", reading.systolicBp(), Types.INTEGER)
                .param("diastolic", reading.diastolicBp(), Types.INTEGER)
                .param("respRate", reading.respRate(), Types.INTEGER)
                .param("temperature", reading.temperature(), Types.DOUBLE)
                .param("consciousness", reading.consciousness().name())
                .update();
    }

    /**
     * {@code ORDER BY at DESC LIMIT 1}, served by the primary key (patient, time).
     *
     * @param patientId the patient
     * @return the patient's reading with the latest time, or empty if there is none
     * @throws IllegalArgumentException if {@code patientId} is null
     */
    @Override
    public Optional<VitalsReading> latestFor(String patientId) {
        if (patientId == null) {
            throw new IllegalArgumentException("patientId is required");
        }
        return jdbc.sql("""
                SELECT patient_id, at, heart_rate, spo2, systolic_bp, diastolic_bp, resp_rate, temperature, consciousness
                FROM vitals WHERE patient_id = :patientId ORDER BY at DESC LIMIT 1
                """)
                .param("patientId", patientId)
                .query((rs, rowNum) -> toReading(rs))
                .optional();
    }

    /**
     * {@code WHERE at >= :from ORDER BY at}, a range scan on the primary key (patient, time).
     *
     * @param patientId the patient
     * @param from      the earliest time to include
     * @return the readings, oldest first
     * @throws IllegalArgumentException if an argument is null
     */
    @Override
    public List<VitalsReading> historyFor(String patientId, Instant from) {
        if (patientId == null || from == null) {
            throw new IllegalArgumentException("patientId and from are required");
        }
        return jdbc.sql("""
                SELECT patient_id, at, heart_rate, spo2, systolic_bp, diastolic_bp, resp_rate, temperature, consciousness
                FROM vitals WHERE patient_id = :patientId AND at >= :from ORDER BY at
                """)
                .param("patientId", patientId)
                .param("from", DbTime.toDb(from))
                .query((rs, rowNum) -> toReading(rs))
                .list();
    }

    // getObject(..., Integer.class) keeps NULL as null; getInt would turn it into 0.
    private static VitalsReading toReading(ResultSet rs) throws SQLException {
        return new VitalsReading(
                rs.getString("patient_id"),
                DbTime.fromDb(rs, "at"),
                rs.getObject("heart_rate", Integer.class),
                rs.getObject("spo2", Integer.class),
                rs.getObject("systolic_bp", Integer.class),
                rs.getObject("diastolic_bp", Integer.class),
                rs.getObject("resp_rate", Integer.class),
                rs.getObject("temperature", Double.class),
                Consciousness.valueOf(rs.getString("consciousness")));
    }
}
