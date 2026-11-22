package com.medidispatch.service.persistence;

import com.medidispatch.core.domain.Contact;
import com.medidispatch.core.domain.Location;
import com.medidispatch.core.domain.Patient;
import com.medidispatch.core.domain.PersonDetails;
import com.medidispatch.core.domain.Sex;
import com.medidispatch.core.port.PatientRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
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
        PersonDetails details = patient.details().orElse(null);
        Contact emergency = patient.emergencyContact().orElse(null);
        jdbc.sql("""
                INSERT INTO patients (id, home, name, age, sex, emergency_name, emergency_phone)
                VALUES (:id, ST_Point(:lon, :lat, 4326)::geography, :name, :age, :sex, :emergencyName, :emergencyPhone)
                ON CONFLICT (id) DO UPDATE SET
                    home = excluded.home, name = excluded.name, age = excluded.age, sex = excluded.sex,
                    emergency_name = excluded.emergency_name, emergency_phone = excluded.emergency_phone
                """)
                .param("id", patient.id())
                .param("lon", patient.location().longitude())
                .param("lat", patient.location().latitude())
                // the types are named because a null value alone does not tell the driver what it is
                .param("name", details == null ? null : details.name(), Types.VARCHAR)
                .param("age", details == null ? null : details.age(), Types.INTEGER)
                .param("sex", details == null ? null : details.sex().name(), Types.VARCHAR)
                .param("emergencyName", emergency == null ? null : emergency.name(), Types.VARCHAR)
                .param("emergencyPhone", emergency == null ? null : emergency.phone(), Types.VARCHAR)
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
        return jdbc.sql("""
                SELECT id, ST_Y(home::geometry) AS lat, ST_X(home::geometry) AS lon,
                       name, age, sex, emergency_name, emergency_phone
                FROM patients WHERE id = :id
                """)
                .param("id", id)
                .query((rs, rowNum) -> toPatient(rs))
                .optional();
    }

    /** Turns the current row back into a patient; NULL details or contact (rows from before V3) stay absent. */
    private static Patient toPatient(ResultSet rs) throws SQLException {
        PersonDetails details = null;
        if (rs.getString("name") != null) {
            details = new PersonDetails(rs.getString("name"), rs.getInt("age"), Sex.valueOf(rs.getString("sex")));
        }
        Contact emergency = null;
        if (rs.getString("emergency_name") != null) {
            emergency = new Contact(rs.getString("emergency_name"), rs.getString("emergency_phone"));
        }
        return new Patient(rs.getString("id"), new Location(rs.getDouble("lat"), rs.getDouble("lon")), details,
                emergency);
    }
}
