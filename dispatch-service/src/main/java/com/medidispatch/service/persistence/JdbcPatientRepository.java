package com.medidispatch.service.persistence;

import com.medidispatch.core.domain.Location;
import com.medidispatch.core.domain.Patient;
import com.medidispatch.core.port.PatientRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Optional;

/**
 * {@link PatientRepository} on Postgres: table {@code patients}, home stored as a PostGIS {@code geography} point.
 *
 * <p>PostGIS takes LONGITUDE first ({@code ST_Point(lon, lat, 4326)}); reading back, {@code ST_Y} is the latitude
 * and {@code ST_X} the longitude ({@code ::geometry} is needed because those two only work on geometry).
 */
public class JdbcPatientRepository implements PatientRepository {

    private final JdbcClient jdbc;

    /**
     * @param jdbc Spring's fluent JDBC API over the application's DataSource
     */
    public JdbcPatientRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Stores the patient, or replaces the stored one with the same id (an "upsert": {@code ON CONFLICT DO UPDATE},
     * where {@code excluded} is the row that was just tried).
     *
     * @param patient the patient to store
     * @throws IllegalArgumentException if {@code patient} is null
     */
    @Override
    public void save(Patient patient) {
        if (patient == null) {
            throw new IllegalArgumentException("patient is required");
        }
        jdbc.sql("""
                INSERT INTO patients (id, home) VALUES (:id, ST_Point(:lon, :lat, 4326)::geography)
                ON CONFLICT (id) DO UPDATE SET home = excluded.home
                """)
                .param("id", patient.id())
                .param("lon", patient.location().longitude())
                .param("lat", patient.location().latitude())
                .update();
    }

    /**
     * @param id the patient id
     * @return the patient, or empty if there is none with that id
     * @throws IllegalArgumentException if {@code id} is null
     */
    @Override
    public Optional<Patient> findById(String id) {
        if (id == null) {
            throw new IllegalArgumentException("id is required");
        }
        return jdbc.sql("SELECT id, ST_Y(home::geometry) AS lat, ST_X(home::geometry) AS lon FROM patients WHERE id = :id")
                .param("id", id)
                .query((rs, rowNum) -> new Patient(rs.getString("id"), new Location(rs.getDouble("lat"), rs.getDouble("lon"))))
                .optional();
    }
}
